package erp.agent;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import nc.vo.pub.AggregatedValueObject;
import nc.vo.pub.CircularlyAccessibleValueObject;
import nc.vo.pub.SuperVO;
import nc.vo.pub.lang.UFBoolean;
import nc.vo.pub.lang.UFDate;
import nc.vo.pub.lang.UFDateTime;
import nc.vo.pub.lang.UFDouble;

/**
 * JSON <-> ERP 运行态对象 互转。
 * VO 统一走 SuperVO/CircularlyAccessibleValueObject 的 attribute 读写，
 * 标量类型映射到 nc.vo.pub.lang.* 封装类。
 */
public final class JsonCodec {

    private JsonCodec() {
    }

    // ---------- Java -> Json ----------

    public static JsonElement toJson(Object obj) {
        if (obj == null) return JsonNull.INSTANCE;
        if (obj instanceof JsonElement) return (JsonElement) obj;
        if (obj instanceof Boolean) return new JsonPrimitive((Boolean) obj);
        if (obj instanceof Number) return new JsonPrimitive((Number) obj);
        if (obj instanceof CharSequence || obj instanceof Character) {
            return new JsonPrimitive(String.valueOf(obj));
        }
        if (obj instanceof UFDouble) return new JsonPrimitive(((UFDouble) obj).doubleValue());
        if (obj instanceof UFBoolean) return new JsonPrimitive(((UFBoolean) obj).booleanValue());
        if (obj instanceof UFDate || obj instanceof UFDateTime || obj instanceof java.util.Date) {
            return new JsonPrimitive(obj.toString());
        }
        if (obj instanceof AggregatedValueObject) return aggToJson((AggregatedValueObject) obj);
        if (obj instanceof SuperVO) return voToJson((SuperVO) obj);
        if (obj instanceof CircularlyAccessibleValueObject) {
            return cavoToJson((CircularlyAccessibleValueObject) obj);
        }
        if (obj instanceof Map) {
            JsonObject jo = new JsonObject();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) obj).entrySet()) {
                jo.add(String.valueOf(e.getKey()), toJson(e.getValue()));
            }
            return jo;
        }
        if (obj instanceof Collection) {
            JsonArray arr = new JsonArray();
            for (Object o : (Collection<?>) obj) arr.add(toJson(o));
            return arr;
        }
        if (obj.getClass().isArray()) {
            JsonArray arr = new JsonArray();
            int n = Array.getLength(obj);
            for (int i = 0; i < n; i++) arr.add(toJson(Array.get(obj, i)));
            return arr;
        }
        if (obj instanceof Throwable) {
            return new JsonPrimitive(String.valueOf(obj));
        }
        return beanToJson(obj, 0);
    }

    private static JsonObject voToJson(SuperVO vo) {
        JsonObject jo = new JsonObject();
        jo.addProperty("_class", vo.getClass().getName());
        String[] names = vo.getAttributeNames();
        if (names != null) {
            for (String name : names) {
                jo.add(name, toJson(safeGet(vo, name)));
            }
        }
        return jo;
    }

    /** 聚合 VO -> {"_class","parent":{...},"children":[...]} */
    private static JsonObject aggToJson(AggregatedValueObject vo) {
        JsonObject jo = new JsonObject();
        jo.addProperty("_class", vo.getClass().getName());
        jo.add("parent", toJson(vo.getParentVO()));
        jo.add("children", toJson(vo.getChildrenVO()));
        return jo;
    }

    private static JsonObject cavoToJson(CircularlyAccessibleValueObject vo) {
        JsonObject jo = new JsonObject();
        jo.addProperty("_class", vo.getClass().getName());
        String[] names = vo.getAttributeNames();
        if (names != null) {
            for (String name : names) {
                try {
                    jo.add(name, toJson(vo.getAttributeValue(name)));
                } catch (Exception ignored) {
                }
            }
        }
        return jo;
    }

    /** 未知 bean：反射无参 getter 输出，限制深度防循环 */
    private static JsonElement beanToJson(Object obj, int depth) {
        if (depth > 5) return new JsonPrimitive(String.valueOf(obj));
        JsonObject jo = new JsonObject();
        jo.addProperty("_class", obj.getClass().getName());
        for (java.lang.reflect.Method m : obj.getClass().getMethods()) {
            if (m.getParameterTypes().length != 0 || m.getReturnType() == void.class) continue;
            String n = m.getName();
            if (!n.startsWith("get") || "getClass".equals(n)) continue;
            String key = Character.toLowerCase(n.charAt(3)) + n.substring(4);
            try {
                Object v = m.invoke(obj);
                jo.add(key, toJson(v));
            } catch (Exception ignored) {
            }
        }
        return jo;
    }

    private static Object safeGet(SuperVO vo, String name) {
        try {
            return vo.getAttributeValue(name);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- Json -> Java ----------

    /** 按目标 Java 类型把 JSON 值转成实参 */
    public static Object toArg(Class<?> type, JsonElement el) throws Exception {
        if (el == null || el.isJsonNull()) return null;
        if (type == String.class) return el.isJsonPrimitive() ? el.getAsString() : el.toString();
        if (type == int.class || type == Integer.class) return el.getAsInt();
        if (type == long.class || type == Long.class) return el.getAsLong();
        if (type == double.class || type == Double.class) return el.getAsDouble();
        if (type == boolean.class || type == Boolean.class) return el.getAsBoolean();
        if (type == float.class || type == Float.class) return el.getAsFloat();
        if (type == short.class || type == Short.class) return el.getAsShort();
        if (type == byte.class || type == Byte.class) return el.getAsByte();
        if (type == char.class || type == Character.class) return el.getAsString().charAt(0);
        if (java.math.BigDecimal.class.isAssignableFrom(type)) return new java.math.BigDecimal(el.getAsString());
        if (java.sql.Timestamp.class.isAssignableFrom(type)) return java.sql.Timestamp.valueOf(el.getAsString());
        if (java.sql.Date.class.isAssignableFrom(type)) return java.sql.Date.valueOf(el.getAsString());
        if (byte[].class == type) return java.util.Base64.getDecoder().decode(el.getAsString());
        if (UFDouble.class.isAssignableFrom(type)) return new UFDouble(el.getAsString());
        if (UFBoolean.class.isAssignableFrom(type)) return new UFBoolean(el.getAsString());
        if (UFDateTime.class.isAssignableFrom(type)) return new UFDateTime(el.getAsString());
        if (UFDate.class.isAssignableFrom(type)) return new UFDate(el.getAsString());
        if (type.isArray()) {
            JsonArray arr = el.getAsJsonArray();
            Class<?> ct = type.getComponentType();
            Object out = Array.newInstance(ct, arr.size());
            for (int i = 0; i < arr.size(); i++) {
                Array.set(out, i, toArg(ct, arr.get(i)));
            }
            return out;
        }
        if (el.isJsonObject()) {
            JsonElement cls = el.getAsJsonObject().get("_class");
            if (cls != null && cls.isJsonPrimitive()
                    && (type.isInterface() || java.lang.reflect.Modifier.isAbstract(type.getModifiers()))) {
                try {
                    type = Class.forName(cls.getAsString());
                } catch (Exception ignored) {
                }
            }
        }
        if (AggregatedValueObject.class.isAssignableFrom(type) && el.isJsonObject()) {
            return toAggVo(type, el.getAsJsonObject());
        }
        if (CircularlyAccessibleValueObject.class.isAssignableFrom(type) && el.isJsonObject()) {
            return toVo(type, el.getAsJsonObject());
        }
        if (type == Object.class) {
            if (el.isJsonPrimitive()) return el.getAsString();
            if (el.isJsonObject() && el.getAsJsonObject().has("_class")) {
                try {
                    Class<?> cls = Class.forName(el.getAsJsonObject().get("_class").getAsString());
                    return toArg(cls, el);
                } catch (Exception ignored) {
                }
            }
            if (el.isJsonObject()) {
                Map<String, Object> m = new java.util.LinkedHashMap<String, Object>();
                for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
                    m.put(e.getKey(), e.getValue().isJsonPrimitive() ? e.getValue().getAsString() : e.getValue().toString());
                }
                return m;
            }
            return el.toString();
        }
        if (Map.class.isAssignableFrom(type) && el.isJsonObject()) {
            Map<String, Object> m = type.isInterface() || java.lang.reflect.Modifier.isAbstract(type.getModifiers())
                    ? new java.util.HashMap<String, Object>() : (Map<String, Object>) type.newInstance();
            for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
                m.put(e.getKey(), e.getValue().isJsonPrimitive() ? e.getValue().getAsString() : e.getValue().toString());
            }
            return m;
        }
        if (java.util.Collection.class.isAssignableFrom(type) && el.isJsonArray()) {
            java.util.Collection<Object> coll = type.isInterface() || java.lang.reflect.Modifier.isAbstract(type.getModifiers())
                    ? new java.util.ArrayList<Object>() : (java.util.Collection<Object>) type.newInstance();
            for (JsonElement e : el.getAsJsonArray()) {
                coll.add(e.isJsonPrimitive() ? e.getAsString() : e.toString());
            }
            return coll;
        }
        if (el.isJsonObject()) return beanToArg(type, el.getAsJsonObject());
        if (el.isJsonPrimitive()) return el.getAsString();
        throw new IllegalArgumentException("unsupported arg type: " + type.getName());
    }

    /**
     * JSON 对象 -> VO：优先按 bean setter（setXxx）精确类型赋值——
     * NC VO 的属性名就是 getter/setter 名，setter 参数类型即真实目标类型；
     * "status"/"dirty" 不是属性而是基类字段，必须走 setStatus/setDirty；
     * 找不到 setter 的键才回退 setAttributeValue（按既有值类型推断）。
     */
    public static Object toVo(Class<?> type, JsonObject jo) throws Exception {
        Object vo = type.newInstance();
        CircularlyAccessibleValueObject cvo = (CircularlyAccessibleValueObject) vo;
        for (Map.Entry<String, JsonElement> e : jo.entrySet()) {
            String attr = e.getKey();
            if ("_class".equals(attr)) continue;
            JsonElement v = e.getValue();
            if (v.isJsonNull()) continue;
            if (trySetBeanSetter(vo, attr, v)) continue;
            Object cur = safeAttrGet(cvo, attr);
            try {
                cvo.setAttributeValue(attr, coerce(v, cur));
            } catch (Exception ignored) {
            }
        }
        return vo;
    }

    /**
     * 聚合 VO（表头+表体单据结构）：JSON {"parent":{...},"children":[{...}]}，
     * 其余键尝试直接 setAttributeValue 到聚合 VO。
     */
    public static Object toAggVo(Class<?> type, JsonObject jo) throws Exception {
        Object vo = type.newInstance();
        AggregatedValueObject avo = (AggregatedValueObject) vo;
        Class<?> parentCls = null, childCls = null;
        for (java.lang.reflect.Field f : type.getDeclaredFields()) {
            Class<?> ft = f.getType();
            if (parentCls == null && CircularlyAccessibleValueObject.class.isAssignableFrom(ft)) {
                parentCls = ft;
            }
            if (!ft.isArray() || !CircularlyAccessibleValueObject.class.isAssignableFrom(ft.getComponentType())) continue;
            // 优先 *ItemVO 结尾的子表（单据主表体），否则取第一个 VO 数组字段
            if (childCls == null || ft.getComponentType().getSimpleName().endsWith("ItemVO")) {
                childCls = ft.getComponentType();
            }
        }
        JsonElement p = jo.get("parent");
        if (p != null && p.isJsonObject() && parentCls != null) {
            avo.setParentVO((CircularlyAccessibleValueObject) toVo(parentCls, p.getAsJsonObject()));
        }
        // 子表类型：优先 JSON 里 "_childrenClass" 显式指定，否则挑 *ItemVO 结尾的 VO 数组字段
        JsonElement cc = jo.get("_childrenClass");
        if (cc != null && cc.isJsonPrimitive()) {
            try {
                childCls = Class.forName(cc.getAsString());
            } catch (Exception ignored) {
            }
        }
        JsonElement c = jo.get("children");
        if (c != null && c.isJsonArray() && childCls != null) {
            Object arr = Array.newInstance(childCls, c.getAsJsonArray().size());
            for (int i = 0; i < c.getAsJsonArray().size(); i++) {
                Array.set(arr, i, toVo(childCls, c.getAsJsonArray().get(i).getAsJsonObject()));
            }
            avo.setChildrenVO((CircularlyAccessibleValueObject[]) arr);
        }
        if (vo instanceof CircularlyAccessibleValueObject) {
            CircularlyAccessibleValueObject cvo = (CircularlyAccessibleValueObject) vo;
            for (Map.Entry<String, JsonElement> e : jo.entrySet()) {
                String attr = e.getKey();
                if (attr.startsWith("_") || "parent".equals(attr) || "children".equals(attr)) continue;
                if (e.getValue().isJsonNull()) continue;
                if (trySetBeanSetter(vo, attr, e.getValue())) continue;
                try {
                    cvo.setAttributeValue(attr, coerce(e.getValue(), safeAttrGet(cvo, attr)));
                } catch (Exception ignored) {
                }
            }
        } else {
            for (Map.Entry<String, JsonElement> e : jo.entrySet()) {
                if ("_class".equals(e.getKey()) || "parent".equals(e.getKey()) || "children".equals(e.getKey())) continue;
                trySetBeanSetter(vo, e.getKey(), e.getValue());
            }
        }
        return vo;
    }

    /** 尝试按 setter 名赋值（返回是否命中）；VO 属性与 status/dirty 都靠它 */
    private static boolean trySetBeanSetter(Object vo, String name, JsonElement v) {
        String setter = "set" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        for (java.lang.reflect.Method m : vo.getClass().getMethods()) {
            if (!m.getName().equalsIgnoreCase(setter) || m.getParameterTypes().length != 1) continue;
            try {
                m.invoke(vo, toArg(m.getParameterTypes()[0], v));
                return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    /** 普通 bean：按 JSON 键找 setXxx setter 填充（如 NormalCondVO{key,value}） */
    private static Object beanToArg(Class<?> type, JsonObject jo) throws Exception {
        Object bean = type.newInstance();
        for (Map.Entry<String, JsonElement> e : jo.entrySet()) {
            if ("_class".equals(e.getKey())) continue;
            String name = e.getKey();
            String setter = "set" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
            java.lang.reflect.Method found = null;
            for (java.lang.reflect.Method m : type.getMethods()) {
                if (m.getName().equalsIgnoreCase(setter) && m.getParameterTypes().length == 1) { found = m; break; }
            }
            if (found == null) continue;
            found.invoke(bean, toArg(found.getParameterTypes()[0], e.getValue()));
        }
        return bean;
    }

    private static Object safeAttrGet(CircularlyAccessibleValueObject vo, String name) {
        try {
            return vo.getAttributeValue(name);
        } catch (Exception e) {
            return null;
        }
    }

    private static Object coerce(JsonElement v, Object current) {
        if (current instanceof UFDouble) return new UFDouble(v.getAsString());
        if (current instanceof UFBoolean) return new UFBoolean(v.getAsString());
        if (current instanceof UFDateTime) return new UFDateTime(v.getAsString());
        if (current instanceof UFDate) return new UFDate(v.getAsString());
        if (current != null) return v.isJsonPrimitive() ? v.getAsString() : v.toString();
        if (v.isJsonPrimitive()) {
            JsonPrimitive p = v.getAsJsonPrimitive();
            if (p.isNumber()) return new UFDouble(p.getAsString());
            if (p.isBoolean()) return new UFBoolean(p.getAsString());
            String s = p.getAsString();
            if (s.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")) return new UFDateTime(s);
            if (s.matches("\\d{4}-\\d{2}-\\d{2}")) return new UFDate(s);
            return s;
        }
        if (v.isJsonObject()) {
            JsonObject jo = v.getAsJsonObject();
            try {
                if (jo.has("_class")) {
                    return toVo(Class.forName(jo.get("_class").getAsString()), jo);
                }
            } catch (Exception e) {
                throw new IllegalArgumentException("bad nested vo: " + e.getMessage());
            }
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            for (Map.Entry<String, JsonElement> e2 : jo.entrySet()) {
                m.put(e2.getKey(), coerce(e2.getValue(), null));
            }
            return m;
        }
        if (v.isJsonArray()) {
            List<Object> list = new ArrayList<Object>();
            for (JsonElement e2 : v.getAsJsonArray()) list.add(coerce(e2, null));
            return list;
        }
        return null;
    }
}
