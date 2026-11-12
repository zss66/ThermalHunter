package de.robv.android.xposed;
import java.util.Set;
public final class XposedBridge {
    public static void log(String text) { throw new RuntimeException("Stub!"); }
    public static void log(Throwable t) { throw new RuntimeException("Stub!"); }
    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> hookClass, String methodName, XC_MethodHook callback) { throw new RuntimeException("Stub!"); }
    public static Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> hookClass, XC_MethodHook callback) { throw new RuntimeException("Stub!"); }
}
