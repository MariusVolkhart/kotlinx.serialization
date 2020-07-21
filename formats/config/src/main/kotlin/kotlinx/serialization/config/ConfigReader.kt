/*
 * Copyright 2017-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.serialization.config

import com.typesafe.config.*
import kotlinx.serialization.*
import kotlinx.serialization.CompositeDecoder.Companion.DECODE_DONE
import kotlinx.serialization.internal.*
import kotlinx.serialization.modules.*

private val SerialKind.listLike get() = this == StructureKind.LIST || this is PolymorphicKind
private val SerialKind.objLike get() = this == StructureKind.CLASS || this == StructureKind.OBJECT

/**
 * Allows [deserialization][decodeFromConfig]
 * of [Config] object from popular Lightbend/config library into Kotlin objects.
 *
 * [Config] object represents "Human-Optimized Config Object Notation" —
 * [HOCON][https://github.com/lightbend/config#using-hocon-the-json-superset].
 *
 * @param configuration configuration for a parser instance.
 * @param serializersModule A [SerializersModule] which should contain registered serializers
 * for [Contextual] and [Polymorphic] serialization, if you have any.
 */
public class ConfigParser(
    private val configuration: ConfigParserConfiguration = ConfigParserConfiguration(),
    override val serializersModule: SerializersModule = EmptySerializersModule
) : SerialFormat {

    public inline fun <reified T : Any> decodeFromConfig(config: Config): T =
        decodeFromConfig(serializersModule.getContextualOrDefault(), config)

    public fun <T> decodeFromConfig(deserializer: DeserializationStrategy<T>, config: Config): T =
        ConfigReader(config).decodeSerializableValue(deserializer)

    @Deprecated(
        "This method was renamed to decodeFromConfig during serialization 1.0 API stabilization",
        level = DeprecationLevel.ERROR,
        replaceWith = ReplaceWith("decodeFromConfig<T>(conf)")
    )
    public inline fun <reified T : Any> parse(conf: Config): T =
        decodeFromConfig(serializersModule.getContextualOrDefault(), conf)

    @Deprecated(
        "This method was renamed to decodeFromConfig during serialization 1.0 API stabilization",
        level = DeprecationLevel.ERROR,
        replaceWith = ReplaceWith("decodeFromConfig(deserializer, conf)")
    )
    public fun <T> parse(conf: Config, deserializer: DeserializationStrategy<T>): T =
        decodeFromConfig(deserializer, conf)

    private abstract inner class ConfigConverter<T> : TaggedDecoder<T>() {
        override val serializersModule: SerializersModule
            get() = this@ConfigParser.serializersModule

        abstract fun getTaggedConfigValue(tag: T): ConfigValue

        private inline fun <reified E : Any> validateAndCast(tag: T, wrappedType: ConfigValueType): E {
            val cfValue = getTaggedConfigValue(tag)
            if (cfValue.valueType() != wrappedType) throw SerializationException("${cfValue.origin().description()} required to be a $wrappedType")
            return cfValue.unwrapped() as E
        }

        private fun getTaggedNumber(tag: T) = validateAndCast<Number>(tag, ConfigValueType.NUMBER)

        override fun decodeTaggedString(tag: T) = validateAndCast<String>(tag, ConfigValueType.STRING)

        override fun decodeTaggedByte(tag: T): Byte = getTaggedNumber(tag).toByte()
        override fun decodeTaggedShort(tag: T): Short = getTaggedNumber(tag).toShort()
        override fun decodeTaggedInt(tag: T): Int = getTaggedNumber(tag).toInt()
        override fun decodeTaggedLong(tag: T): Long = getTaggedNumber(tag).toLong()
        override fun decodeTaggedFloat(tag: T): Float = getTaggedNumber(tag).toFloat()
        override fun decodeTaggedDouble(tag: T): Double = getTaggedNumber(tag).toDouble()

        override fun decodeTaggedChar(tag: T): Char {
            val s = validateAndCast<String>(tag, ConfigValueType.STRING)
            if (s.length != 1) throw SerializationException("String \"$s\" is not convertible to Char")
            return s[0]
        }

        override fun decodeTaggedValue(tag: T): Any = getTaggedConfigValue(tag).unwrapped()

        override fun decodeTaggedNotNullMark(tag: T) = getTaggedConfigValue(tag).valueType() != ConfigValueType.NULL

        override fun decodeTaggedEnum(tag: T, enumDescription: SerialDescriptor): Int {
            val s = validateAndCast<String>(tag, ConfigValueType.STRING)
            return enumDescription.getElementIndexOrThrow(s)
        }
    }

    private inner class ConfigReader(val conf: Config) : ConfigConverter<String>() {
        private var ind = -1

        override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
            while (++ind < descriptor.elementsCount) {
                val name = descriptor.getTag(ind)
                if (conf.hasPathOrNull(name)) {
                    return ind
                }
            }
            return DECODE_DONE
        }

        private fun composeName(parentName: String, childName: String) =
            if (parentName.isEmpty()) childName else parentName + "." + childName

        override fun SerialDescriptor.getTag(index: Int): String =
            composeName(currentTagOrNull ?: "", getConventionElementName(index))

        private fun SerialDescriptor.getConventionElementName(index: Int): String {
            val originalName = getElementName(index)
            return if (!configuration.useConfigNamingConvention) originalName
            else originalName.replace(NAMING_CONVENTION_REGEX) { "-${it.value.toLowerCase()}" }
        }

        override fun getTaggedConfigValue(tag: String): ConfigValue {
            return conf.getValue(tag)
        }

        override fun decodeTaggedNotNullMark(tag: String): Boolean {
            return !conf.getIsNull(tag)
        }

        override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder =
            when {
                descriptor.kind.listLike -> ListConfigReader(conf.getList(currentTag))
                descriptor.kind.objLike -> if (ind > -1) ConfigReader(conf.getConfig(currentTag)) else this
                descriptor.kind == StructureKind.MAP -> MapConfigReader(conf.getObject(currentTag))
                else -> this
            }
    }

    private inner class ListConfigReader(private val list: ConfigList) : ConfigConverter<Int>() {
        private var ind = -1

        override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder =
            when {
                descriptor.kind.listLike -> ListConfigReader(list[currentTag] as ConfigList)
                descriptor.kind.objLike -> ConfigReader((list[currentTag] as ConfigObject).toConfig())
                descriptor.kind == StructureKind.MAP -> MapConfigReader(list[currentTag] as ConfigObject)
                else -> this
            }

        override fun SerialDescriptor.getTag(index: Int) = index

        override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
            ind++
            return if (ind > list.size - 1) DECODE_DONE else ind
        }

        override fun getTaggedConfigValue(tag: Int): ConfigValue = list[tag]
    }

    private inner class MapConfigReader(map: ConfigObject) : ConfigConverter<Int>() {

        private var ind = -1
        private val keys: List<String>
        private val values: List<ConfigValue>

        init {
            val entries = map.entries.toList() // to fix traversal order
            keys = entries.map(MutableMap.MutableEntry<String, ConfigValue>::key)
            values = entries.map(MutableMap.MutableEntry<String, ConfigValue>::value)
        }

        private val indexSize = values.size * 2

        override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder =
            when {
                descriptor.kind.listLike -> ListConfigReader(values[currentTag / 2] as ConfigList)
                descriptor.kind.objLike -> ConfigReader((values[currentTag / 2] as ConfigObject).toConfig())
                descriptor.kind == StructureKind.MAP -> MapConfigReader(values[currentTag / 2] as ConfigObject)
                else -> this
            }

        override fun SerialDescriptor.getTag(index: Int) = index

        override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
            ind++
            return if (ind >= indexSize) CompositeDecoder.DECODE_DONE else ind
        }

        override fun getTaggedConfigValue(tag: Int): ConfigValue {
            val idx = tag / 2
            return if (tag % 2 == 0) { // entry as string
                ConfigValueFactory.fromAnyRef(keys[idx])
            } else {
                values[idx]
            }
        }
    }

    @Suppress("UNUSED")
    companion object {
        @Deprecated(
            "This method was renamed to decodeFromConfig during serialization 1.0 API stabilization",
            level = DeprecationLevel.ERROR,
            replaceWith = ReplaceWith("ConfigParser.decodeFromConfig(serial, conf)")
        )
        public fun <T> parse(conf: Config, serial: DeserializationStrategy<T>): T = ConfigParser().decodeFromConfig(
            serial,
            conf
        )

        @Deprecated(
            "This method was renamed to decodeFromConfig during serialization 1.0 API stabilization",
            level = DeprecationLevel.ERROR,
            replaceWith = ReplaceWith("ConfigParser.decodeFromConfig(conf)")
        )
        public inline fun <reified T : Any> parse(conf: Config): T = ConfigParser().decodeFromConfig(serializer(), conf)

        public inline fun <reified T : Any> decodeFromConfig(config: Config): T = ConfigParser().decodeFromConfig(serializer(), config)

        public fun <T> decodeFromConfig(deserializer: DeserializationStrategy<T>, config: Config): T =
            ConfigParser().decodeFromConfig(deserializer, config)

        private val NAMING_CONVENTION_REGEX by lazy { "[A-Z]".toRegex() }
    }


    internal fun SerialDescriptor.getElementIndexOrThrow(name: String): Int {
        val index = getElementIndex(name)
        if (index == CompositeDecoder.UNKNOWN_NAME)
            throw SerializationException("$serialName does not contain element with name '$name'")
        return index
    }
}