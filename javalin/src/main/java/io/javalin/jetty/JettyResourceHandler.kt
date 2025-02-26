/*
 * Javalin - https://javalin.io
 * Copyright 2017 David Åse
 * Licensed under Apache 2.0: https://github.com/tipsy/javalin/blob/master/LICENSE
 */

package io.javalin.jetty

import io.javalin.config.PrivateConfig
import io.javalin.http.Context
import io.javalin.http.staticfiles.Location
import io.javalin.http.staticfiles.StaticFileConfig
import io.javalin.util.JavalinException
import io.javalin.util.JavalinLogger
import io.javalin.util.javalinLazy
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.http.HttpServletResponseWrapper
import org.eclipse.jetty.http.MimeTypes
import org.eclipse.jetty.io.EofException
import org.eclipse.jetty.server.HttpConnection
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.handler.ResourceHandler
import org.eclipse.jetty.util.URIUtil
import org.eclipse.jetty.util.resource.EmptyResource
import org.eclipse.jetty.util.resource.Resource
import java.io.File
import java.nio.file.AccessDeniedException
import io.javalin.http.staticfiles.ResourceHandler as JavalinResourceHandler

class JettyResourceHandler(val pvt: PrivateConfig) : JavalinResourceHandler {

    fun init() { // we delay the creation of ConfigurableHandler objects to get our logs in order during startup
        handlers.addAll(lateInitConfigs.map { ConfigurableHandler(it, pvt.server!!) })
    }

    private val lateInitConfigs = mutableListOf<StaticFileConfig>()
    private val handlers = mutableListOf<ConfigurableHandler>()

    override fun addStaticFileConfig(config: StaticFileConfig): Boolean =
        if (pvt.server?.isStarted == true) handlers.add(ConfigurableHandler(config, pvt.server!!)) else lateInitConfigs.add(config)

    override fun canHandle(ctx: Context) = nonSkippedHandlers().any { handler ->
        return try {
            fileOrWelcomeFile(handler, ctx.target) != null
        } catch (e: Exception) {
            e.message?.contains("Rejected alias reference") == true ||  // we want to say these are un-handleable (404)
                e.message?.contains("Failed alias check") == true // we want to say these are un-handleable (404)
        }
    }

    override fun handle(ctx: Context): Boolean {
        nonSkippedHandlers().forEach { handler ->
            try {
                val target = ctx.target
                val fileOrWelcomeFile = fileOrWelcomeFile(handler, target)
                if (fileOrWelcomeFile != null) {
                    handler.config.headers.forEach { ctx.header(it.key, it.value) } // set user headers
                    return when (handler.config.precompress) {
                        true -> JettyPrecompressingResourceHandler.handle(target, fileOrWelcomeFile, ctx, pvt.compressionStrategy)
                        false -> {
                            ctx.res().contentType = null // Jetty will only set the content-type if it's null
                            runCatching { // we wrap the response to compress it with javalin's compression strategy
                                handler.handle(target, jettyRequest(), ctx.req(), CompressingResponseWrapper(ctx))
                            }.isSuccess
                        }
                    }
                }
            } catch (e: Exception) { // it's fine, we'll just 404
                if (e !is EofException) { // EofException is thrown when the client disconnects, which is fine
                    JavalinLogger.info("Exception occurred while handling static resource", e)
                }
            }
        }
        return false
    }

    private fun Resource?.fileOrNull(): Resource? = this?.takeIf { it.exists() && !it.isDirectory }
    private fun fileOrWelcomeFile(handler: ResourceHandler, target: String): Resource? =
        handler.getResource(target)?.fileOrNull() ?: handler.getResource("${target.removeSuffix("/")}/index.html")?.fileOrNull()

    private fun jettyRequest() = HttpConnection.getCurrentConnection().httpChannel.request as Request

    private fun nonSkippedHandlers() =
        handlers.asSequence().filter { !it.config.skipFileFunction(jettyRequest()) }

    private val Context.target get() = this.req().requestURI.removePrefix(this.req().contextPath)

}

open class ConfigurableHandler(val config: StaticFileConfig, jettyServer: Server) : ResourceHandler() {

    init {
        JavalinLogger.info("Static file handler added: ${config.refinedToString()}. File system location: '${getResourceBase(config)}'")
        resourceBase = getResourceBase(config)
        isDirAllowed = false
        isEtags = true
        server = jettyServer
        mimeTypes = MimeTypes()
        config.mimeTypes.getMapping().forEach { (ext, mimeType) ->
            mimeTypes.addMimeMapping(ext, mimeType)
        }
        start()
    }

    override fun getResource(path: String): Resource {
        val aliasResource by javalinLazy { baseResource!!.addPath(URIUtil.canonicalPath(path)) }
        return when {
            config.directory == "META-INF/resources/webjars" ->
                Resource.newClassPathResource("META-INF/resources$path") ?: EmptyResource.INSTANCE

            config.aliasCheck != null && aliasResource.isAlias ->
                if (config.aliasCheck?.check(path, aliasResource) == true) aliasResource else throw AccessDeniedException("Failed alias check")

            config.hostedPath == "/" -> super.getResource(path) // same as regular ResourceHandler
            path == config.hostedPath -> super.getResource("/")
            path.startsWith(config.hostedPath + "/") -> super.getResource(path.removePrefix(config.hostedPath))
            else -> EmptyResource.INSTANCE // files that don't start with hostedPath should not be accessible
        }
    }

    private fun getResourceBase(config: StaticFileConfig): String {
        val noSuchDirMessage = "Static resource directory with path: '${config.directory}' does not exist."
        val classpathHint = "Depending on your setup, empty folders might not get copied to classpath."
        if (config.location == Location.CLASSPATH) {
            return Resource.newClassPathResource(config.directory)?.toString() ?: throw JavalinException("$noSuchDirMessage $classpathHint")
        }
        if (!File(config.directory).exists()) {
            throw JavalinException(noSuchDirMessage)
        }
        return config.directory
    }

}

private class CompressingResponseWrapper(private val ctx: Context) : HttpServletResponseWrapper(ctx.res()) {
    override fun getOutputStream(): ServletOutputStream = ctx.outputStream()
}
