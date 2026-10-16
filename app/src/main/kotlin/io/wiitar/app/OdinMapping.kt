/*
 * wiitar-android — Wii guitar to Android gamepad bridge
 * Copyright (C) 2026 wiitar-android contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.wiitar.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.IInterface
import android.os.Looper
import dalvik.system.DexFile
import dalvik.system.PathClassLoader
import java.lang.reflect.Method

/**
 * Talks to the AYN Odin's controller-mapping app (com.odin.mapping).
 *
 * That app hides the kernel's "Nintendo Wii Remote Guitar" device and
 * republishes it without the whammy bar or the full touch strip. It keeps a
 * device-name blacklist (addBlackListDeviceName / removeBlackListDeviceName /
 * getBlackListDeviceNames), and the probe showed it exports a service
 * (com.ro.mapping.action.MAPPING_SERVICE). Adding the guitar's name there should
 * make it leave Wii guitars alone, so the bridge can read the real device.
 *
 * Its binder interface isn't published, so this loads the interface class from
 * the app's own APK (system APKs are world-readable) and calls it by method name.
 */
object OdinMapping {
    const val PACKAGE = "com.odin.mapping"
    private const val ACTION = "com.ro.mapping.action.MAPPING_SERVICE"
    const val GUITAR_NAME = "Nintendo Wii Remote Guitar"

    fun isPresent(context: Context): Boolean = try {
        context.packageManager.getApplicationInfo(PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun apkPath(context: Context): String = context.packageManager.getApplicationInfo(PACKAGE, 0).sourceDir

    private fun loader(context: Context) = PathClassLoader(apkPath(context), context.classLoader)

    @Suppress("DEPRECATION") // DexFile is the only way to list another APK's classes.
    private fun mappingClassNames(context: Context): List<String> =
        DexFile(apkPath(context)).entries().toList().filter { it.startsWith("com.ro.mapping") }

    private val interfaces = HashMap<String, Class<*>?>()

    /** The AIDL interface in the APK that declares [method], if any. Slow the first time: call off the main thread. */
    @Synchronized
    private fun interfaceWith(context: Context, method: String): Class<*>? = interfaces.getOrPut(method) {
        val loader = loader(context)
        mappingClassNames(context).asSequence()
            .mapNotNull { runCatching { Class.forName(it, false, loader) }.getOrNull() }
            .firstOrNull { cls ->
                cls.isInterface && IInterface::class.java.isAssignableFrom(cls) && cls.methods.any { it.name == method }
            }
    }

    /** For the probe: the service's exported state and every API-looking class with its methods. */
    fun describe(context: Context): String = buildString {
        try {
            val services = context.packageManager.queryIntentServices(Intent(ACTION).setPackage(PACKAGE), 0)
            for (s in services) {
                appendLine("service ${s.serviceInfo.name} exported=${s.serviceInfo.exported} permission=${s.serviceInfo.permission}")
            }
            if (services.isEmpty()) appendLine("service: not visible to this app")
            val loader = loader(context)
            val interesting = mappingClassNames(context).filter { name ->
                listOf("Api", "Stub", "IMapping", "Black", "CommonSettings", "Service").any { name.contains(it) }
            }
            for (name in interesting.take(40)) {
                val cls = runCatching { Class.forName(name, false, loader) }.getOrNull() ?: continue
                val methods = cls.declaredMethods.filter { !it.name.startsWith("access$") }.take(40)
                    .joinToString { m -> "${m.name}(${m.parameterTypes.joinToString { it.simpleName }})" }
                appendLine("$name${if (cls.isInterface) " [interface]" else ""}: $methods")
            }
        } catch (e: Throwable) {
            appendLine("couldn't inspect $PACKAGE: $e")
        }
    }

    /**
     * Binds to the mapping service and calls [method] (with [arg] if given). [done]
     * gets a human-readable result on the main thread.
     */
    fun call(context: Context, method: String, arg: String?, done: (String) -> Unit) {
        Thread({ callBlocking(context.applicationContext, method, arg, done) }, "wiitar-odin-mapping").start()
    }

    private fun callBlocking(context: Context, method: String, arg: String?, done: (String) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        val iface = try {
            interfaceWith(context, method)
        } catch (e: Throwable) {
            main.post { done("Couldn't read the Odin mapping app: $e") }
            return
        } ?: run {
            main.post { done("The Odin mapping app's service doesn't offer $method.") }
            return
        }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val result = try {
                    val stub = Class.forName(iface.name + "\$Stub", true, iface.classLoader)
                    val proxy = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
                    val m: Method = iface.methods.first { it.name == method }
                    val value = if (arg != null && m.parameterTypes.size == 1) m.invoke(proxy, arg) else m.invoke(proxy)
                    when (value) {
                        null, Unit -> "OK"
                        is Array<*> -> value.joinToString().ifEmpty { "(empty)" }
                        is Collection<*> -> value.joinToString().ifEmpty { "(empty)" }
                        else -> value.toString()
                    }
                } catch (e: Throwable) {
                    "Call failed: ${(e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e}"
                }
                runCatching { context.unbindService(this) }
                main.post { done(result) }
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        val bound = try {
            context.bindService(Intent(ACTION).setPackage(PACKAGE), connection, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            main.post { done("Not allowed to talk to the Odin mapping app: ${e.message}") }
            return
        }
        if (!bound) main.post { done("The Odin mapping app's service isn't available.") }
    }
}
