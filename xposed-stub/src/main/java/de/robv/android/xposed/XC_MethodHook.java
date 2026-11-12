package de.robv.android.xposed;
import java.lang.reflect.Member;
import de.robv.android.xposed.callbacks.XCallback;
public abstract class XC_MethodHook extends XCallback {
    public XC_MethodHook() { throw new RuntimeException("Stub!"); }
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}
    public static final class MethodHookParam extends XCallback.Param {
        public Member method;
        public Object thisObject;
        public Object[] args;
        public Object getResult() { throw new RuntimeException("Stub!"); }
        public void setResult(Object result) { throw new RuntimeException("Stub!"); }
        public Throwable getThrowable() { throw new RuntimeException("Stub!"); }
    }
    public class Unhook { public void unhook() { throw new RuntimeException("Stub!"); } }
}
