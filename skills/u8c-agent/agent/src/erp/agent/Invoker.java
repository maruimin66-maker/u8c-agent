package erp.agent;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

/**
 * 通用远程调用：按接口名 lookup 代理，按方法名+参数类型反射调用。
 */
public final class Invoker {

    private final Session session;

    public Invoker(Session session) {
        this.session = session;
    }

    public Object invoke(String itfName, String methodName,
                         List<String> argTypeNames, JsonArray args) throws Exception {
        Object svc = session.lookup(itfName);
        Class<?> itf = loadClass(itfName);

        Class<?>[] argTypes = new Class<?>[argTypeNames.size()];
        for (int i = 0; i < argTypeNames.size(); i++) {
            argTypes[i] = loadClass(argTypeNames.get(i));
        }
        Method m = itf.getMethod(methodName, argTypes);
        JsonArray arr = args != null ? args : new JsonArray();
        Object[] javaArgs = new Object[argTypes.length];
        for (int i = 0; i < argTypes.length; i++) {
            JsonElement el = i < arr.size() ? arr.get(i) : null;
            javaArgs[i] = JsonCodec.toArg(argTypes[i], el);
        }
        return m.invoke(svc, javaArgs);
    }

    /** 原生对象直调：用于服务端接口间传递真实 VO（跳过 JSON 序列化往返） */
    public Object invokeRaw(String itfName, String methodName,
                            List<String> argTypeNames, Object... args) throws Exception {
        Object svc = session.lookup(itfName);
        Class<?> itf = loadClass(itfName);
        Class<?>[] argTypes = new Class<?>[argTypeNames.size()];
        for (int i = 0; i < argTypeNames.size(); i++) {
            argTypes[i] = loadClass(argTypeNames.get(i));
        }
        Method m = itf.getMethod(methodName, argTypes);
        return m.invoke(svc, args);
    }

    /** 列出接口全部方法签名，供调用前探测 */
    public List<String> describe(String itfName) throws Exception {
        Class<?> itf = loadClass(itfName);
        List<String> out = new ArrayList<String>();
        for (Method m : itf.getMethods()) {
            StringBuilder sb = new StringBuilder();
            sb.append(m.getReturnType().getSimpleName()).append(' ');
            sb.append(m.getName()).append('(');
            Class<?>[] pts = m.getParameterTypes();
            for (int i = 0; i < pts.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(pts[i].getName());
            }
            sb.append(')');
            out.add(sb.toString());
        }
        return out;
    }

    private static Class<?> loadClass(String name) throws ClassNotFoundException {
        if ("int".equals(name)) return int.class;
        if ("long".equals(name)) return long.class;
        if ("double".equals(name)) return double.class;
        if ("boolean".equals(name)) return boolean.class;
        if ("byte".equals(name)) return byte.class;
        if ("void".equals(name)) return void.class;
        return Class.forName(name);
    }
}
