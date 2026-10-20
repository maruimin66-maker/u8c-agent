package erp.agent;

import java.lang.reflect.Array;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import nc.vo.pub.AggregatedValueObject;
import nc.vo.pub.CircularlyAccessibleValueObject;

/**
 * 平台单据动作管线（单据保存/通用平台动作的共用链路）：
 *   checkWorkitemOnSave -> 前台业务插件(PROC_CLIENT=8) -> IplatFormEntry.processAction
 * 与 NC 客户端 PfUtilClient.runAction 同序，适用于 SAVE/EDIT 等平台动作。
 */
public final class Flow {

    private Flow() {
    }

    /**
     * 对聚合 VO 执行平台动作（默认 SAVE 语义）。
     * 返回 processAction 原始返回值；过程信息写入 out（wfVoPresent/clientPlugins/pluginStopFlow）。
     */
    public static Object process(Session s, Invoker inv, String action, String billType,
                                 AggregatedValueObject vo, JsonObject out) throws Exception {
        HashMap<String, Object> ep = new HashMap<String, Object>();
        Object wfVo = inv.invokeRaw("nc.itf.uap.pf.IWorkflowMachine",
                "checkWorkitemOnSave",
                Arrays.asList("java.lang.String", "java.lang.String", "java.lang.String",
                        "nc.vo.pub.AggregatedValueObject", "java.util.HashMap"),
                action, billType, now(), vo, ep);
        out.addProperty("wfVoPresent", wfVo != null);
        if (wfVo != null) {
            Object appArgs = beanGet(wfVo, "applicationArgs");
            if (appArgs != null) {
                Object wfBt = beanGet(wfVo, "billType");
                runClientPlugins(wfBt != null ? wfBt.toString() : billType,
                        vo, appArgs, out);
            }
        }
        HashMap<String, Object> eparam = null;
        if (wfVo == null) {
            eparam = new HashMap<String, Object>();
            eparam.put("notechecked", "notechecked");
        }
        return inv.invokeRaw("nc.itf.uap.pf.IplatFormEntry", "processAction",
                Arrays.asList("java.lang.String", "java.lang.String", "java.lang.String",
                        "nc.vo.pub.pf.PfUtilWorkFlowVO",
                        "nc.vo.pub.AggregatedValueObject", "java.lang.Object",
                        "java.util.HashMap"),
                action, billType, now(), wfVo, vo, null, eparam);
    }

    /**
     * 前台业务插件（executeBusinessPlugin 等价）：wfVo.applicationArgs 非空时，
     * 按单据类型取 PROC_CLIENT(8) 插件类并 execute；插件 stopFlow 时只记录不阻断由调用方决定。
     */
    public static void runClientPlugins(String billType, AggregatedValueObject billVo,
                                        Object appArgs, JsonObject out) {
        try {
            Class<?> pfc = Class.forName("nc.bs.pf.pub.PfDataCache");
            Object list = pfc.getMethod("getBillType2Info", String.class, int.class)
                    .invoke(null, billType, 8);
            if (!(list instanceof List)) return;
            JsonArray ran = new JsonArray();
            for (Object bt2 : (List<?>) list) {
                Object clsName = beanGet(bt2, "classname");
                if (clsName == null) continue;
                Class<?> pCls = Class.forName(clsName.toString());
                Object plugin = pCls.getDeclaredConstructor().newInstance();
                Class<?> ctxCls = Class.forName("nc.vo.pub.pf.PfClientBizProcessContext");
                Object ctx = ctxCls.getDeclaredConstructor().newInstance();
                ctxCls.getMethod("setBillvo", AggregatedValueObject.class)
                        .invoke(ctx, billVo);
                ctxCls.getMethod("setArgsList", ArrayList.class).invoke(ctx, appArgs);
                ctxCls.getMethod("setMakeBill", boolean.class).invoke(ctx, true);
                Object ret = Class.forName("nc.vo.pub.pf.IPFClientBizProcess")
                        .getMethod("execute", java.awt.Container.class, ctxCls)
                        .invoke(plugin, null, ctx);
                ran.add(clsName.toString());
                if (ret != null) {
                    Object stop = beanGet(ret, "stopFlow");
                    if (stop != null && truthy(stop)) {
                        out.addProperty("pluginStopFlow", clsName.toString());
                        break;
                    }
                }
            }
            out.add("clientPlugins", ran);
        } catch (Exception e) {
            out.addProperty("clientPluginError", String.valueOf(e));
        }
    }

    // ---------- 反射小工具（包内共享） ----------

    /** JavaBean getter（用于非 CircularlyAccessibleValueObject 的 VO） */
    static Object beanGet(Object o, String name) {
        if (o instanceof CircularlyAccessibleValueObject) {
            Object v = ((CircularlyAccessibleValueObject) o).getAttributeValue(name);
            if (v != null) return v;
        }
        try {
            String g = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
            return o.getClass().getMethod(g).invoke(o);
        } catch (Exception e) {
            return null;
        }
    }

    static void call(Object target, String name, Class<?>[] types, Object... args)
            throws Exception {
        target.getClass().getMethod(name, types).invoke(target, args);
    }

    static boolean truthy(Object o) {
        if (o instanceof Boolean) return (Boolean) o;
        String v = String.valueOf(o);
        return "Y".equals(v) || "true".equalsIgnoreCase(v);
    }

    static String str(Object o) {
        return o == null ? null : o.toString();
    }

    static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }
}
