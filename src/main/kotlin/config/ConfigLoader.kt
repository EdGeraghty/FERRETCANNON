package config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import java.io.File
import java.io.InputStream

object ConfigLoader {
    private val mapper = ObjectMapper(YAMLFactory()).apply {
        registerModule(KotlinModule.Builder().build())
    }

    fun loadConfig(configPath: String = "config.yml"): ServerConfig {
        return try {
            val configFile = File(configPath)
            if (configFile.exists()) {
                println("Loading configuration from: ${configFile.absolutePath}")
                configFile.inputStream().use { loadFromStream(it) }
            } else {
                println("Configuration file not found at: ${configFile.absolutePath}")
                println("Loading default configuration")
                ServerConfig()
            }
        } catch (e: Exception) {
            println("Error loading configuration: ${e.message}")
            println("Loading default configuration")
            ServerConfig()
        }
    }

    fun loadFromStream(inputStream: InputStream): ServerConfig {
        return try {
            mapper.readValue(inputStream, ServerConfig::class.java)
        } catch (e: Exception) {
            println("Error parsing configuration: ${e.message}")
            ServerConfig()
        }
    }

    fun loadFromResource(resourcePath: String): ServerConfig {
        return try {
            val resourceStream = this::class.java.classLoader.getResourceAsStream(resourcePath)
            if (resourceStream != null) {
                resourceStream.use { loadFromStream(it) }
            } else {
                println("Configuration resource not found: $resourcePath")
                ServerConfig()
            }
        } catch (e: Exception) {
            println("Error loading configuration from resource: ${e.message}")
            ServerConfig()
        }
    }

    fun createDefaultConfigFile(configPath: String = "config.yml") {
        val configFile = File(configPath)
        if (!configFile.exists()) {
            try {
                val defaultConfig = ServerConfig()
                mapper.writerWithDefaultPrettyPrinter().writeValue(configFile, defaultConfig)
                println("Created default configuration file: ${configFile.absolutePath}")
            } catch (e: Exception) {
                println("Error creating default configuration file: ${e.message}")
            }
        }
    }
}
