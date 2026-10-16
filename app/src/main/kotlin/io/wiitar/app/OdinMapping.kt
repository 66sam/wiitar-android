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
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import dalvik.system.DexFile
import dalvik.system.PathClassLoader
import java.lang.reflect.Modifier

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
 * Its code is obfuscated: the service's methods are renamed (A, B, C…), so they
 * can't be called by name. But the generated binder stub keeps a table from
 * transaction number to the original method name (that's where the probe found
 * the names). This loads the app's classes from its APK (system APKs are
 * world-readable), finds that table, and calls the service by number.
 */
object OdinMapping {
    const val PACKAGE = "com.odin.mapping"
    private const val ACTION = "com.ro.mapping.action.MAPPING_SERVICE"
    const val GUITAR_NAME = "Nintendo Wii Remote Guitar"

    /** Binder transaction numbers start at 1 (IBinder.FIRST_CALL_TRANSACTION); AIDL interfaces stay well below this. */
    private const val MAX_TRANSACTION = 300

    fun isPresent(context: Context): Boolean = try {
        context.packageManager.getApplicationInfo(PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun apkPath(context: Context): String = context.packageManager.getApplicationInfo(PACKAGE, 0).sourceDir

    /** The app's own classes; libraries bundled in the APK can't hold its service. */
    @Suppress("DEPRECATION") // DexFile is the only way to list another APK's classes.
    private fun appClassNames(context: Context): List<String> =
        DexFile(apkPath(context)).entries().toList().filter { name ->
            LIBRARY_PREFIXES.none { name.startsWith(it) }
        }

    private val LIBRARY_PREFIXES = listOf("androidx.", "android.support.", "kotlin", "com.google.", "okhttp3.", "okio.")

    /** A binder class in the app and its method name → transaction number table (empty if none was found). */
    private class Stub(val cls: Class<*>, val codes: Map<String, Int>)

    private var stubs: List<Stub>? = null

    /** Every binder class in the app, with its transaction names. Slow the first time: call off the main thread. */
    @Synchronized
    private fun stubs(context: Context): List<Stub> = stubs ?: run {
        val loader = PathClassLoader(apkPath(context), context.classLoader)
        val binders = appClassNames(context).mapNotNull { name ->
            runCatching { Class.forName(name, false, loader) }.getOrNull()
                ?.takeIf { Binder::class.java.isAssignableFrom(it) && it != Binder::class.java }
        }
        binders.map { Stub(it, transactionNames(it)) }.also { stubs = it }
    }

    /**
     * Method name → transaction number for binder class [cls]. AIDL stubs name their
     * transactions in TRANSACTION_* constants and in an (int) → String lookup, which
     * shrinking may leave on an abstract parent class; this looks there too.
     */
    private fun transactionNames(cls: Class<*>): Map<String, Int> {
        val codes = HashMap<String, Int>()
        val instance by lazy { allocate(cls) }
        var next: Class<*>? = cls
        while (next != null && next != Binder::class.java) {
            val k: Class<*> = next
            for (field in runCatching { k.declaredFields }.getOrDefault(emptyArray())) {
                if (Modifier.isStatic(field.modifiers) && field.type == Int::class.javaPrimitiveType && field.name.startsWith("TRANSACTION_")) {
                    runCatching { field.isAccessible = true; codes[field.name.removePrefix("TRANSACTION_")] = field.getInt(null) }
                }
            }
            for (m in runCatching { k.declaredMethods }.getOrDefault(emptyArray())) {
                if (m.returnType != String::class.java || !m.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))) continue
                val target: Any? = if (Modifier.isStatic(m.modifiers)) null else instance ?: continue
                runCatching {
                    m.isAccessible = true
                    for (code in 1..MAX_TRANSACTION) {
                        val name = runCatching { m.invoke(target, code) as? String }.getOrNull() ?: continue
                        if (name.isNotEmpty() && name.all { it.isLetterOrDigit() || it == '_' }) codes.putIfAbsent(name, code)
                    }
                }
            }
            next = k.superclass
        }
        return codes
    }

    /** An instance without running a constructor, for a name lookup that isn't static. */
    private fun allocate(cls: Class<*>): Any? = runCatching {
        if (Modifier.isAbstract(cls.modifiers) || cls.isInterface) return null
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, cls)
    }.getOrNull()

    private fun codeFor(context: Context, method: String): Int? =
        stubs(context).firstNotNullOfOrNull { it.codes[method] }

    /** For the probe: the service, its interface name, and every binder class in the app with its transactions. */
    fun describe(context: Context): String = buildString {
        try {
            val services = context.packageManager.queryIntentServices(Intent(ACTION).setPackage(PACKAGE), 0)
            for (s in services) {
                appendLine("service ${s.serviceInfo.name} exported=${s.serviceInfo.exported} permission=${s.serviceInfo.permission}")
            }
            if (services.isEmpty()) appendLine("service: not visible to this app")
            appendLine("interface: ${interfaceDescriptor(context)}")
            val found = stubs(context)
            appendLine("${appClassNames(context).size} app classes, ${found.size} binder classes")
            for (stub in found) {
                val c = stub.cls
                val chain = generateSequence(c.superclass) { it.superclass }.takeWhile { it != Any::class.java }.joinToString(" < ") { it.name }
                appendLine("${c.name}${if (Modifier.isAbstract(c.modifiers)) " [abstract]" else ""} < $chain")
                appendLine("  implements ${c.interfaces.joinToString { it.name }}")
                if (stub.codes.isEmpty()) {
                    // No name table: list the methods so the transactions can be matched by hand.
                    for (m in c.declaredMethods) appendLine("  ${m.name}(${m.parameterTypes.joinToString { it.simpleName }}): ${m.returnType.simpleName}")
                }
                for ((name, code) in stub.codes.entries.sortedBy { it.value }) appendLine("  $code $name")
            }
        } catch (e: Throwable) {
            appendLine("couldn't inspect $PACKAGE: $e")
        }
    }

    /** Binds to the service and reads its interface name. Blocks for up to a few seconds. */
    private fun interfaceDescriptor(context: Context): String {
        val latch = java.util.concurrent.CountDownLatch(1)
        var result = "no answer"
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                result = runCatching { binder?.interfaceDescriptor ?: "none" }.getOrElse { it.toString() }
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        return try {
            if (!context.bindService(Intent(ACTION).setPackage(PACKAGE), connection, Context.BIND_AUTO_CREATE)) return "can't bind"
            latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
            runCatching { context.unbindService(connection) }
            result
        } catch (e: Exception) {
            e.toString()
        }
    }

    /**
     * Binds to the mapping service and calls [method] (with the string [arg] if
     * given). [done] gets a human-readable result on the main thread; for
     * get… methods that's the returned list, comma-separated.
     */
    fun call(context: Context, method: String, arg: String?, done: (String) -> Unit) {
        Thread({ callBlocking(context.applicationContext, method, arg, done) }, "wiitar-odin-mapping").start()
    }

    private fun callBlocking(context: Context, method: String, arg: String?, done: (String) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        val code = try {
            codeFor(context, method)
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
                    transact(binder ?: error("no binder"), code, method, arg)
                } catch (e: Throwable) {
                    "Call failed: $e"
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

    /** One AIDL call by hand: interface token, optional string argument, then the reply. */
    private fun transact(binder: IBinder, code: Int, method: String, arg: String?): String {
        val descriptor = binder.interfaceDescriptor ?: error("the service has no interface descriptor")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(descriptor)
            if (arg != null) data.writeString(arg)
            if (!binder.transact(code, data, reply, 0)) error("transaction $code not handled")
            reply.readException()
            // A String[] and a List<String> are written the same way.
            return if (method.startsWith("get")) {
                reply.createStringArrayList().orEmpty().joinToString().ifEmpty { "(empty)" }
            } else {
                "OK"
            }
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
