package misk.hibernate

import io.opentracing.Tracer
import io.opentracing.tag.Tags
import misk.backoff.ExponentialBackoff
import misk.hibernate.Shard.Companion.SINGLE_SHARD_SET
import misk.jdbc.map
import misk.jdbc.uniqueString
import misk.logging.getLogger
import misk.tracing.traceWithSpan
import org.hibernate.FlushMode
import org.hibernate.SessionFactory
import org.hibernate.StaleObjectStateException
import org.hibernate.exception.LockAcquisitionException
import java.io.Closeable
import java.sql.Connection
import java.sql.SQLRecoverableException
import java.sql.SQLSyntaxErrorException
import java.time.Duration
import java.util.EnumSet
import javax.inject.Provider
import javax.persistence.OptimisticLockException
import kotlin.reflect.KClass

private val logger = getLogger<RealTransacter>()

internal class RealTransacter private constructor(
  private val qualifier: KClass<out Annotation>,
  private val sessionFactoryProvider: Provider<SessionFactory>,
  private val threadLatestSession: ThreadLocal<RealSession>,
  private val options: TransacterOptions,
  private val queryTracingListener: QueryTracingListener,
  private val tracer: Tracer?
) : Transacter {

  constructor(
    qualifier: KClass<out Annotation>,
    sessionFactoryProvider: Provider<SessionFactory>,
    queryTracingListener: QueryTracingListener,
    tracer: Tracer?
  ) : this(
      qualifier,
      sessionFactoryProvider,
      ThreadLocal(),
      TransacterOptions(),
      queryTracingListener,
      tracer
  )

  private val sessionFactory
    get() = sessionFactoryProvider.get()

  override val inTransaction: Boolean
    get() = threadLatestSession.get()?.inTransaction ?: false

  override fun isCheckEnabled(check: Check): Boolean {
    val session = threadLatestSession.get()
    return session == null || !session.inTransaction || !session.disabledChecks.contains(check)
  }

  override fun <T> transaction(block: (session: Session) -> T): T {
    return maybeWithTracing(APPLICATION_TRANSACTION_SPAN_NAME) {
      transactionWithRetriesInternal(block)
    }
  }

  private fun <T> transactionWithRetriesInternal(block: (session: Session) -> T): T {
    require(options.maxAttempts > 0)

    val backoff = ExponentialBackoff(
        Duration.ofMillis(options.minRetryDelayMillis),
        Duration.ofMillis(options.maxRetryDelayMillis),
        Duration.ofMillis(options.retryJitterMillis)
    )
    var attempt = 0

    while (true) {
      try {
        attempt++
        val result = transactionInternal(block)

        if (attempt > 1) {
          logger.info {
            "retried ${qualifier.simpleName} transaction succeeded (${attemptNote(attempt)})"
          }
        }

        return result
      } catch (e: Exception) {
        if (!isRetryable(e)) throw e

        if (attempt >= options.maxAttempts) {
          logger.info {
            "${qualifier.simpleName} recoverable transaction exception " +
                "(${attemptNote(attempt)}), no more attempts"
          }
          throw e
        }

        val sleepDuration = backoff.nextRetry()
        logger.info(e) {
          "${qualifier.simpleName} recoverable transaction exception " +
              "(${attemptNote(attempt)}), will retry after a $sleepDuration delay"
        }

        if (!sleepDuration.isZero) {
          Thread.sleep(sleepDuration.toMillis())
        }
      }
    }
  }

  /**
   * Returns a string describing the most recent attempt. This includes whether the attempt used the
   * same connection which might help in diagnosing stale data problems.
   */
  private fun attemptNote(attempt: Int): String {
    if (attempt == 1) return "attempt 1"
    val latestSession = threadLatestSession.get()!!
    if (latestSession.sameConnection) return "attempt $attempt, same connection"
    return "attempt $attempt, different connection"
  }

  private fun <T> transactionInternal(block: (session: Session) -> T): T {
    return maybeWithTracing(DB_TRANSACTION_SPAN_NAME) { transactionInternalSession(block) }
  }

  private fun <T> transactionInternalSession(block: (session: Session) -> T): T {
    return withSession { session ->
      val transaction = maybeWithTracing(DB_BEGIN_SPAN_NAME) {
        session.hibernateSession.beginTransaction()!!
      }
      try {
        val result = block(session)

        // Flush any changes to the databased before commit
        session.hibernateSession.flush()
        session.preCommit()
        maybeWithTracing(DB_COMMIT_SPAN_NAME) { transaction.commit() }
        session.postCommit()
        result
      } catch (e: Throwable) {
        if (transaction.isActive) {
          try {
            maybeWithTracing(DB_ROLLBACK_SPAN_NAME) {
              transaction.rollback()
            }
          } catch (suppressed: Exception) {
            e.addSuppressed(suppressed)
          }
        }
        throw e
      } finally {
        // For any reason if tracing was left open, end it.
        queryTracingListener.endLastSpan()
      }
    }
  }

  override fun retries(maxAttempts: Int): Transacter = withOptions(
      options.copy(maxAttempts = maxAttempts))

  override fun allowCowrites(): Transacter {
    val disableChecks = options.disabledChecks.clone()
    disableChecks.add(Check.COWRITE)
    return withOptions(
        options.copy(disabledChecks = disableChecks))
  }

  override fun noRetries(): Transacter = withOptions(options.copy(maxAttempts = 1))

  override fun readOnly(): Transacter = withOptions(options.copy(readOnly = true))

  private fun withOptions(options: TransacterOptions): Transacter =
      RealTransacter(
          qualifier,
          sessionFactoryProvider,
          threadLatestSession,
          options,
          queryTracingListener,
          tracer
      )

  private fun <T> withSession(block: (session: RealSession) -> T): T {
    val hibernateSession = sessionFactory.openSession()
    val realSession = RealSession(
        hibernateSession = hibernateSession,
        readOnly = options.readOnly,
        disabledChecks = options.disabledChecks,
        predecessor = threadLatestSession.get()
    )

    // Note that the RealSession is closed last so that close hooks run after the thread locals and
    // Hibernate Session have been released. This way close hooks can start their own transactions.
    realSession.use {
      hibernateSession.use {
        useSession(realSession) {
          return block(realSession)
        }
      }
    }
  }

  private fun isRetryable(th: Throwable): Boolean {
    return when (th) {
      is RetryTransactionException,
      is StaleObjectStateException,
      is LockAcquisitionException,
      is SQLRecoverableException,
      is OptimisticLockException -> true
      else -> th.cause?.let { isRetryable(it) } ?: false
    }
  }

  // NB: all options should be immutable types as copy() is shallow.
  internal data class TransacterOptions(
    val maxAttempts: Int = 2,
    val disabledChecks: EnumSet<Check> = EnumSet.noneOf(Check::class.java),
    val minRetryDelayMillis: Long = 100,
    val maxRetryDelayMillis: Long = 200,
    val retryJitterMillis: Long = 400,
    val readOnly: Boolean = false
  )

  companion object {
    const val APPLICATION_TRANSACTION_SPAN_NAME = "app-db-transaction"
    const val DB_TRANSACTION_SPAN_NAME = "db-session"
    const val DB_BEGIN_SPAN_NAME = "db-begin"
    const val DB_COMMIT_SPAN_NAME = "db-commit"
    const val DB_ROLLBACK_SPAN_NAME = "db-rollback"
    const val TRANSACTER_SPAN_TAG = "hibernate-transacter"
  }

  internal class RealSession(
    override val hibernateSession: org.hibernate.Session,
    private val readOnly: Boolean,
    var disabledChecks: EnumSet<Check>,
    predecessor: RealSession?
  ) : Session, Closeable {
    private val preCommitHooks = mutableListOf<() -> Unit>()
    private val postCommitHooks = mutableListOf<() -> Unit>()
    private val sessionCloseHooks = mutableListOf<() -> Unit>()
    private val rootConnection = hibernateSession.rootConnection
    internal val sameConnection = predecessor?.rootConnection == rootConnection
    internal var inTransaction = false

    init {
      if (readOnly) {
        hibernateSession.isDefaultReadOnly = true
        hibernateSession.hibernateFlushMode = FlushMode.MANUAL
      }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : DbEntity<T>> save(entity: T): Id<T> {
      check(!readOnly) { "Saving isn't permitted in a read only session." }
      return when (entity) {
        is DbChild<*, *> -> (hibernateSession.save(entity) as Gid<*, *>).id
        is DbRoot<*> -> hibernateSession.save(entity)
        is DbUnsharded<*> -> hibernateSession.save(entity)
        else -> throw IllegalArgumentException(
            "You need to sub-class one of [DbChild, DbRoot, DbUnsharded]")
      } as Id<T>
    }

    override fun <T : DbEntity<T>> delete(entity: T) {
      check(!readOnly) {
        "Deleting isn't permitted in a read only session."
      }
      return hibernateSession.delete(entity)
    }

    override fun <T : DbEntity<T>> load(id: Id<T>, type: KClass<T>): T {
      return hibernateSession.get(type.java, id)
    }

    override fun <R : DbRoot<R>, T : DbSharded<R, T>> loadSharded(
      gid: Gid<R, T>,
      type: KClass<T>
    ): T {
      return hibernateSession.get(type.java, gid)
    }

    override fun <T : DbEntity<T>> loadOrNull(id: Id<T>, type: KClass<T>): T? {
      return hibernateSession.get(type.java, id)
    }

    override fun shards(): Set<Shard> {
      return useConnection { connection ->
        if (connection.isVitess()) {
          connection.createStatement().use { s ->
            var shards = s.executeQuery("SHOW VITESS_SHARDS")
                .map { rs -> parseShard(rs.getString(1)) }
                .toSet()
            if (shards.isEmpty()) {
              // HACK It seems sometimes this fails to return shards,
              // as a workaround we run it again to decrease the probability of this happening
              // If we fail the second time as well we throw a recoverable exception
              // TODO(jontirsen): Unhack this when this issue is fixed:
              //   https://github.com/vitessio/vitess/issues/5038
              shards = s.executeQuery("SHOW VITESS_SHARDS")
                  .map { rs -> parseShard(rs.getString(1)) }
                  .toSet()
              if (shards.isEmpty()) {
                throw SQLRecoverableException("Failed to load list of shards")
              }
            }
            shards
          }
        } else {
          SINGLE_SHARD_SET
        }
      }
    }

    private fun parseShard(string: String): Shard {
      val (keyspace, shard) = string.split('/', limit = 2)
      return Shard(Keyspace(keyspace), shard)
    }

    override fun <T> target(shard: Shard, function: () -> T): T {
      return useConnection { connection ->
        if (connection.isVitess()) {
          val previousTarget = withoutChecks {
            // TODO we need to parse out the tablet type (replica or master) from the current target and keep that when we target the new shard
            // We should only change the shard we're targeting, not the tablet type
            val previousTarget =
                connection.createStatement().use { statement ->
                  statement.executeQuery("SHOW VITESS_TARGET").uniqueString()
                }
            connection.createStatement().use { statement ->
              statement.execute("USE `$shard`")
            }

            previousTarget
          }
          try {
            function()
          } finally {
            withoutChecks {
              val sql = if (previousTarget.isBlank()) {
                "USE"
              } else {
                "USE `$previousTarget`"
              }
              connection.createStatement().use { it.execute(sql) }
            }
          }
        } else {
          function()
        }
      }
    }

    override fun <T> useConnection(work: (Connection) -> T): T {
      return hibernateSession.doReturningWork(work)
    }

    internal fun preCommit() {
      preCommitHooks.forEach { preCommitHook ->
        // Propagate hook exceptions up to the transacter so that the the transaction is rolled
        // back and the error gets returned to the application.
        preCommitHook()
      }
    }

    override fun onPreCommit(work: () -> Unit) {
      preCommitHooks.add(work)
    }

    internal fun postCommit() {
      postCommitHooks.forEach { postCommitHook ->
        try {
          postCommitHook()
        } catch (th: Throwable) {
          throw PostCommitHookFailedException(th)
        }
      }
    }

    override fun onPostCommit(work: () -> Unit) {
      postCommitHooks.add(work)
    }

    override fun close() {
      sessionCloseHooks.forEach { sessionCloseHook ->
        sessionCloseHook()
      }
    }

    override fun onSessionClose(work: () -> Unit) {
      sessionCloseHooks.add(work)
    }

    override fun <T> withoutChecks(vararg checks: Check, body: () -> T): T {
      val previous = disabledChecks
      val actualChecks = if (checks.isEmpty()) {
        EnumSet.allOf(Check::class.java)
      } else {
        EnumSet.of(checks[0], *checks)
      }
      disabledChecks = actualChecks
      return try {
        body()
      } finally {
        disabledChecks = previous
      }
    }

    /**
     * Returns the physical JDBC connection of this session. Hibernate creates one-time-use wrappers
     * around the physical connections that talk to the database. This unwraps those so we can
     * tell when a connection is involved in a stale data problem.
     */
    private val org.hibernate.Session.rootConnection: Connection
      get() {
        var result: Connection = doReturningWork { connection -> connection }
        while (result.isWrapperFor(Connection::class.java)) {
          val unwrapped = result.unwrap(Connection::class.java)
          if (unwrapped == result) break
          result = unwrapped
        }
        return result
      }
  }

  private fun <T> maybeWithTracing(spanName: String, block: () -> T): T {
    return if (tracer != null) tracer.traceWithSpan(spanName) { span ->
      Tags.COMPONENT.set(span, TRANSACTER_SPAN_TAG)
      block()
    } else {
      block()
    }
  }

  private inline fun <R> useSession(session: RealSession, block: () -> R): R {
    val previous = threadLatestSession.get()
    check(previous == null || !previous.inTransaction) { "Attempted to start a nested session" }

    threadLatestSession.set(session)
    session.inTransaction = true
    try {
      return block()
    } finally {
      session.inTransaction = false
    }
  }
}

fun Connection.isVitess(): Boolean {
  if (metaData.driverName.startsWith("Vitess")) {
    return true
  }
  // If we're using the MySQL Driver we can check if the underlying connection
  // uses some Vitess specific syntax
  try {
    this.createStatement().use { s -> s.executeQuery("SHOW VITESS_TARGET") }
    return true
  } catch (e: SQLSyntaxErrorException) {
    return false
  }
}
