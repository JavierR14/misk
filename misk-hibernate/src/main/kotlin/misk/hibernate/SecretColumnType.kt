package misk.hibernate

import com.google.crypto.tink.Aead
import misk.crypto.AeadKeyManager
import org.hibernate.HibernateException
import org.hibernate.engine.spi.SharedSessionContractImplementor
import org.hibernate.id.UUIDGenerator
import org.hibernate.usertype.ParameterizedType
import org.hibernate.usertype.UserType
import java.io.Serializable
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.util.Properties
import org.hibernate.type.spi.TypeConfiguration
import org.hibernate.type.spi.TypeConfigurationAware
import java.security.GeneralSecurityException
import java.util.Objects

internal class SecretColumnType : UserType, ParameterizedType, TypeConfigurationAware {
  companion object {
    const val FIELD_ENCRYPTION_KEY_NAME: String = "key_name"
  }
  private lateinit var keyName: String
  private lateinit var aead: Aead
  private lateinit var _typeConfiguration: TypeConfiguration
  private val aadGenerator: UUIDGenerator =
      UUIDGenerator.buildSessionFactoryUniqueIdentifierGenerator()

  override fun setTypeConfiguration(typeConfiguration: TypeConfiguration) {
    _typeConfiguration = typeConfiguration
  }

  override fun getTypeConfiguration(): TypeConfiguration = _typeConfiguration

  override fun setParameterValues(parameters: Properties) {
    keyName = parameters.getProperty(FIELD_ENCRYPTION_KEY_NAME)
    val keyManager = _typeConfiguration.metadataBuildingContext.bootstrapContext.
        serviceRegistry.injector.getInstance(AeadKeyManager::class.java)
    aead = keyManager[keyName]
  }

  override fun hashCode(x: Any): Int = (x as ByteArray).hashCode()

  override fun deepCopy(value: Any?) = (value as ByteArray?)?.copyOf()

  override fun replace(original: Any?, target: Any?, owner: Any?) = (original as ByteArray).copyOf()

  override fun equals(x: Any?, y: Any?): Boolean = Objects.equals(x, y)

  override fun returnedClass() = ByteArray::class.java

  override fun assemble(cached: Serializable?, owner: Any?): ByteArray {
    return aead.decrypt(cached as ByteArray, null)
  }

  /**
   * This method is used by Hibernate when caching values, see [org.hibernate.type.Type.disassemble].
   * This implementation makes sure that data is stored encrypted even when being cached in memory.
   */
  override fun disassemble(value: Any?): Serializable {
    return aead.encrypt(value as ByteArray, null)
  }

  override fun nullSafeSet(
    st: PreparedStatement,
    value: Any?,
    index: Int,
    session: SharedSessionContractImplementor?
  ) {
    if (value == null) {
      st.setNull(index, Types.VARBINARY)
      st.setNull(index + 1, Types.VARBINARY)
    } else {
      value as ByteArray

      val aad = aadGenerator.generate(session, value).toString().toByteArray()
      val encrypted = aead.encrypt(value, aad)
      st.setBytes(index, encrypted)
      st.setBytes(index + 1, aad)
    }
  }

  override fun nullSafeGet(
    rs: ResultSet?,
    names: Array<out String>,
    session: SharedSessionContractImplementor?,
    owner: Any?
  ): Any? {
    val result = rs?.getBytes(names[0])
    val aad = rs?.getBytes(names[1])
    return result?.let { try {
      aead.decrypt(it, aad)
      } catch (e: GeneralSecurityException) {
        throw HibernateException(e)
      }
    }
  }

  override fun isMutable() = false

  override fun sqlTypes() = intArrayOf(Types.VARBINARY, Types.VARBINARY)
}