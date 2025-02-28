package misk.hibernate

import com.google.common.util.concurrent.AbstractIdleService
import com.google.common.util.concurrent.Service
import misk.environment.Environment
import misk.healthchecks.HealthCheck
import misk.healthchecks.HealthStatus
import misk.jdbc.DataSourceType
import kotlin.reflect.KClass

class SchemaMigratorService internal constructor(
  private val qualifier: KClass<out Annotation>,
  private val environment: Environment,
  private val schemaMigratorProvider: javax.inject.Provider<SchemaMigrator>, // Lazy!
  private val config: misk.jdbc.DataSourceConfig
) : AbstractIdleService(), HealthCheck {
  private lateinit var migrationState: MigrationState

  override fun startUp() {
    val schemaMigrator = schemaMigratorProvider.get()
    if (environment == Environment.TESTING || environment == Environment.DEVELOPMENT) {
      if (config.type != DataSourceType.VITESS) {
        val appliedMigrations = schemaMigrator.initialize()
        migrationState = schemaMigrator.applyAll("SchemaMigratorService", appliedMigrations)
      } else {
        // vttestserver automatically applies migrations
        migrationState = MigrationState(emptyMap())
      }
    } else {
      migrationState = schemaMigrator.requireAll()
    }
  }

  override fun shutDown() {
  }

  override fun status(): HealthStatus {
    val state = state()
    if (state != Service.State.RUNNING) {
      return HealthStatus.unhealthy("SchemaMigratorService: ${qualifier.simpleName} is $state")
    }

    return HealthStatus.healthy(
        "SchemaMigratorService: ${qualifier.simpleName} is migrated: $migrationState")
  }
}
