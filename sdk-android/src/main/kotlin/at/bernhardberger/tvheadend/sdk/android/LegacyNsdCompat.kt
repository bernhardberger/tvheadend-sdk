package at.bernhardberger.tvheadend.sdk.android

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.lang.reflect.InvocationTargetException
import java.net.InetAddress

internal object LegacyNsdCompat {
    fun resolveService(
        manager: NsdManager,
        serviceInfo: NsdServiceInfo,
        listener: NsdManager.ResolveListener,
    ) {
        // These APIs are required below 34 but deprecated by compile SDK 36.
        invoke(
            manager,
            "resolveService",
            arrayOf(NsdServiceInfo::class.java, NsdManager.ResolveListener::class.java),
            serviceInfo,
            listener,
        )
    }

    fun hostAddress(serviceInfo: NsdServiceInfo): String? =
        (invoke(serviceInfo, "getHost", emptyArray()) as InetAddress?)?.hostAddress

    private fun invoke(
        receiver: Any,
        name: String,
        parameterTypes: Array<Class<*>>,
        vararg arguments: Any,
    ): Any? = try {
        receiver.javaClass.getMethod(name, *parameterTypes).invoke(receiver, *arguments)
    } catch (exception: InvocationTargetException) {
        when (val cause = exception.cause) {
            is RuntimeException -> throw cause
            is Error -> throw cause
            else -> throw IllegalStateException("Legacy NSD invocation failed", cause)
        }
    } catch (exception: ReflectiveOperationException) {
        throw IllegalStateException("Legacy NSD API is unavailable", exception)
    }
}
