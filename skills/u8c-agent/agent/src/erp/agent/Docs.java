package erp.agent;

import java.net.InetAddress;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * 单据创建编排：采购订单（IOrder.saveBatch）与采购合同（IplatFormEntry SAVEBASE）。
 * 只负责补默认字段、发请求、回查验证；业务字段全部来自调用方 head/items。
 */
public final class Docs {

    private static final int VO_NEW = 2; // nc.vo.pub.VOStatus.NEW

    private Docs() {
    }

    // ---------- 采购订单 ----------

    /**
     * spec: {"head":{...OrderHeaderVO字段...}, "items":[{...OrderItemVO字段...}],
     *        "vordercode":"可选单号（缺省自动生成 AG+时间戳，走 needCreateNewBillCode=false）"}
     * 返回: {"pk","code","verified":true}
     */
    public static JsonObject createPurchaseOrder(Session s, JsonObject spec) throws Exception {
        JsonObject head = (JsonObject) new com.google.gson.JsonParser().parse(req(spec, "head").toString());
        JsonArray items = spec.has("items") && spec.get("items").isJsonArray()
                ? (JsonArray) new com.google.gson.JsonParser().parse(spec.getAsJsonArray("items").toString()) : new JsonArray();
        if (items.size() == 0) throw new IllegalArgumentException("items 不能为空");

        String corp = head.get("pk_corp").getAsString();
        String headPk = s.genOid(corp);
        String code = spec.has("vordercode") ? spec.get("vordercode").getAsString()
                : autoCode(spec.has("codePrefix") ? spec.get("codePrefix").getAsString() : "PO");

        putDefault(head, "corderid", headPk);
        putDefault(head, "vordercode", code);
        putDefault(head, "forderstatus", -1);
        putDefault(head, "nversion", 1);
        putDefault(head, "bislatest", true);
        putDefault(head, "breturn", false);
        putDefault(head, "bisreplenish", false);
        putDefault(head, "dr", 0);
        putDefault(head, "status", VO_NEW);
        putDefault(head, "dorderdate", today());
        putDefault(head, "nexchangeotobrate", 1);
        putDefault(head, "cuserid", s.getUserId());
        putDefault(head, "coperator", s.getUserId());

        JsonArray children = new JsonArray();
        for (int i = 0; i < items.size(); i++) {
            JsonObject it = items.get(i).getAsJsonObject();
            putDefault(it, "corder_bid", s.genOid(corp));
            it.addProperty("corderid", head.get("corderid").getAsString());
            putDefault(it, "pk_corp", corp);
            putDefault(it, "pk_arrvcorp", corp);
            putDefault(it, "pk_invoicecorp", corp);
            putDefault(it, "pk_reqcorp", corp);
            putDefault(it, "crowno", String.valueOf((i + 1) * 10));
            putDefault(it, "dr", 0);
            putDefault(it, "status", VO_NEW);
            putDefault(it, "iisactive", 0);
            putDefault(it, "iisreplenish", 0);
            putDefault(it, "forderrowstatus", 0);
            putDefault(it, "idiscounttaxtype", 1);
            putDefault(it, "ndiscountrate", 100);
            putDefault(it, "nexchangeotobrate", 1);
            putDefault(it, "breceiveplan", false);
            putDefault(it, "blargess", false);
            putDefault(it, "bnegative", false);
            putDefault(it, "dorderdate", head.get("dorderdate").getAsString());
            if (it.has("cinventoryid") && !it.has("cmangid")) it.add("cmangid", it.get("cinventoryid"));
            children.add(it);
        }

        JsonObject vo = new JsonObject();
        vo.addProperty("_class", "nc.vo.po.OrderVO");
        vo.addProperty("status", VO_NEW);
        vo.addProperty("needCreateNewBillCode", false);
        vo.addProperty("groupPkCorp", s.getPkCorp());
        if (head.has("cbiztype")) vo.add("pkBusinessType", head.get("cbiztype"));
        vo.add("parent", head);
        vo.add("children", children);

        JsonArray args = new JsonArray();
        JsonArray arr = new JsonArray();
        arr.add(vo);
        args.add(arr);
        invoke(s, "nc.itf.po.IOrder", "saveBatch",
                Arrays.asList("[Lnc.vo.po.OrderVO;"), args);

        return verify("po_order", "vordercode", code, s,
                new String[]{"corderid", "vordercode", "forderstatus", "pk_corp"},
                "corderid", "vordercode");
    }

    // ---------- 采购合同 ----------

    /**
     * spec: {"head":{...ManageHeaderVO字段...}, "items":[{...ManageItemVO字段...}],
     *        "ct_code":"可选合同号", "billType":"Z2(默认)"}
     * 返回: {"pk","code","verified":true}
     */
    public static JsonObject createPurchaseContract(Session s, JsonObject spec) throws Exception {
        JsonObject head = (JsonObject) new com.google.gson.JsonParser().parse(req(spec, "head").toString());
        JsonArray items = spec.has("items") && spec.get("items").isJsonArray()
                ? (JsonArray) new com.google.gson.JsonParser().parse(spec.getAsJsonArray("items").toString()) : new JsonArray();
        String corp = head.get("pk_corp").getAsString();
        String billType = spec.has("billType") ? spec.get("billType").getAsString()
                : Profile.get("contract.sourceBillType") != null
                        ? Profile.get("contract.sourceBillType") : "Z2";
        String headPk = s.genOid(corp);
        // autoBillCode=true（默认）：走服务端单据号规则生成正式编号；
        // 显式传 ct_code 时按给定编号落库（needCreateNewBillCode 语义）
        boolean autoBillCode = !spec.has("ct_code") && !head.has("ct_code")
                && (!spec.has("autoBillCode") || spec.get("autoBillCode").getAsBoolean());
        String code = autoBillCode ? fetchBillCode(s, billType, corp)
                : (spec.has("ct_code") ? spec.get("ct_code").getAsString() : head.get("ct_code").getAsString());

        putDefault(head, "pk_ct_manage", headPk);
        putDefault(head, "ct_code", code);
        putDefault(head, "ctflag", 0);
        putDefault(head, "activeflag", 0);
        putDefault(head, "version", "1.0");
        putDefault(head, "ifearly", "N");
        putDefault(head, "bsc", "N");
        putDefault(head, "bordernumexec", "N");
        putDefault(head, "dr", 0);
        putDefault(head, "status", VO_NEW);
        putDefault(head, "operid", s.getUserId());
        putDefault(head, "coperatoridnow", s.getUserId());
        if (!head.has("invcontroltype") && head.has("pk_ct_type")) {
            Integer ctl = invControlType(s, head.get("pk_ct_type").getAsString());
            if (ctl != null) head.addProperty("invcontroltype", ctl);
        }
        if (!head.has("subscribedate")) head.addProperty("subscribedate", today());
        putDefault(head, "operdate", today());

        JsonArray children = new JsonArray();
        for (int i = 0; i < items.size(); i++) {
            JsonObject it = items.get(i).getAsJsonObject();
            putDefault(it, "pk_ct_manage_b", s.genOid(corp));
            it.addProperty("pk_ct_manage", head.get("pk_ct_manage").getAsString());
            putDefault(it, "pk_corp", corp);
            putDefault(it, "crowno", String.valueOf((i + 1) * 10));
            putDefault(it, "dr", 0);
            putDefault(it, "status", VO_NEW);
            children.add(it);
        }

        JsonObject log = new JsonObject();
        log.addProperty("pkcorp", corp);
        log.addProperty("companyname", s.getCorpName());
        log.addProperty("enterip", localIp());

        JsonObject vo = new JsonObject();
        vo.addProperty("_class", "nc.vo.ct.pub.ManageVO");
        vo.addProperty("_childrenClass", "nc.vo.ct.pub.ManageItemVO");
        vo.addProperty("status", VO_NEW);
        vo.addProperty("billStatus", 0);
        vo.addProperty("editStatus", 2);
        vo.addProperty("isSaveCheck", true);
        vo.addProperty("billType", billType);
        vo.add("operatelogVO", log);
        vo.add("parent", head);
        vo.add("children", children);

        JsonObject eparam = new JsonObject();
        eparam.addProperty("notechecked", "notechecked");
        JsonArray args = new JsonArray();
        args.add(new JsonPrimitive("SAVEBASE"));
        args.add(new JsonPrimitive(billType));
        args.add(new JsonPrimitive(today()));
        args.add(com.google.gson.JsonNull.INSTANCE);
        args.add(vo);
        args.add(com.google.gson.JsonNull.INSTANCE);
        args.add(eparam);
        invoke(s, "nc.itf.uap.pf.IplatFormEntry", "processAction",
                Arrays.asList("java.lang.String", "java.lang.String", "java.lang.String",
                        "nc.vo.pub.pf.PfUtilWorkFlowVO", "nc.vo.pub.AggregatedValueObject",
                        "java.lang.Object", "java.util.HashMap"), args);

        return verify("ct_manage", "ct_code", code, s,
                new String[]{"pk_ct_manage", "ct_code", "ct_name", "ctflag", "pk_corp"},
                "pk_ct_manage", "ct_code");
    }

    // ---------- 参照合同生成订单 + 审核 + 预付款（复刻 UI 链路 Z2->21） ----------

    /**
     * spec: {"ct_code"|"pk_ct_manage":..., "bizTypeName":"<业务类型名(缺省取合同 bizTypeField 字段值,默认def8)>",
     *        "head":{vdef4:链接,vdef5:执行人,dorderdate?,pk_arrvstoorg?,cwarehouseid?,...覆盖},
     *        "items":[{pk_ct_manage_b|crowno, ...行覆盖}], "approve":true,
     *        "prepay":{"amount":5000}}
     * 返回: {orderPk, orderCode, orderExisted?, audited, forderstatus, payVouchid?, payBillCode?}
     */
    public static JsonObject orderFromContract(Session s, JsonObject spec) throws Exception {
        String ctPk = optStr(spec, "pk_ct_manage");
        String ctCode = optStr(spec, "ct_code");
        if (ctPk == null && ctCode == null) throw new IllegalArgumentException("需要 ct_code 或 pk_ct_manage");

        String where = ctPk != null ? "pk_ct_manage='" + ctPk + "'"
                : "ct_code='" + ctCode.replace("'", "''") + "'";
        JsonArray ctRows = tableQuery(s, "ct_manage",
                new String[]{"pk_ct_manage", "ct_code", "pk_corp", "ctflag"}, where);
        if (ctRows.size() == 0) throw new IllegalStateException("合同不存在: " + where);
        JsonArray ctRow = ctRows.get(0).getAsJsonArray();
        ctPk = ctRow.get(0).getAsString();
        ctCode = ctRow.get(1).getAsString();
        String corp = ctRow.get(2).getAsString();
        if (ctRow.get(3).getAsInt() != 2) throw new IllegalStateException("合同非生效状态(ctflag!=2): " + ctCode);

        JsonObject out = new JsonObject();
        out.addProperty("ctCode", ctCode);
        out.addProperty("corp", corp);

        // 幂等：从合同反查现存订单（排除已废弃），存在则复用不重复创建
        String orderPk = pickLiveOrder(s, ctPk);
        if (orderPk != null) {
            out.addProperty("orderExisted", true);
        } else {
            JsonObject ctJson = loadCtJson(s, corp, where, ctCode);
            createOrderFromCt(s, spec, ctJson, ctPk, corp);
            orderPk = pickLiveOrder(s, ctPk);
            if (orderPk == null)
                throw new IllegalStateException("订单写入后按 ccontractid 回查为空: " + ctPk);
        }

        // 已存在订单若仍是自由态且表体缺仓库：更新保存路径服务端状态位不可靠，改为 DISCARD 废弃后重建
        JsonArray headChk = tableQuery(s, "po_order", new String[]{"forderstatus"},
                "corderid='" + orderPk + "'");
        int curStatus = headChk.size() > 0 && !headChk.get(0).getAsJsonArray().get(0).isJsonNull()
                ? headChk.get(0).getAsJsonArray().get(0).getAsInt() : -1;
        if (curStatus == 0) {
            JsonArray whChk = tableQuery(s, "po_order_b", new String[]{"corder_bid", "cwarehouseid"},
                    "corderid='" + orderPk + "'");
            boolean lackWh = false;
            for (JsonElement e : whChk) {
                if (e.getAsJsonArray().get(1).isJsonNull()) lackWh = true;
            }
            if (lackWh) {
                JsonElement oldVo = JsonCodec.toJson(new Invoker(s).invokeRaw(
                        "nc.itf.po.IOrder", "queryOrderVOByKey",
                        Arrays.asList("java.lang.String"), orderPk));
                for (JsonElement e : whChk) {
                    releasePkLock(s, e.getAsJsonArray().get(0).isJsonNull()
                            ? null : e.getAsJsonArray().get(0).getAsString());
                }
                releasePkLock(s, orderPk);
                discardOrder(s, oldVo);
                JsonObject ctJson = loadCtJson(s, corp, where, ctCode);
                createOrderFromCt(s, spec, ctJson, ctPk, corp);
                orderPk = pickLiveOrder(s, ctPk);
                if (orderPk == null)
                    throw new IllegalStateException("重建订单回查为空: " + ctPk);
                out.addProperty("orderRecreated", true);
            }
        }

        // 订单头现状
        JsonArray oh = tableQuery(s, "po_order", new String[]{"vordercode", "forderstatus"},
                "corderid='" + orderPk + "'");
        if (oh.size() == 0) throw new IllegalStateException("订单回查为空: " + orderPk);
        String orderCode = oh.get(0).getAsJsonArray().get(0).getAsString();
        int forderstatus = oh.get(0).getAsJsonArray().get(1).getAsInt();
        out.addProperty("orderPk", orderPk);
        out.addProperty("orderCode", orderCode);

        if (spec.has("approve") && spec.get("approve").getAsBoolean() && forderstatus != 3) {
            JsonElement voJson = JsonCodec.toJson(new Invoker(s).invokeRaw(
                    "nc.itf.po.IOrder", "queryOrderVOByKey",
                    Arrays.asList("java.lang.String"), orderPk));
            releasePkLock(s, orderPk);
            if (voJson.isJsonObject()) voJson.getAsJsonObject().addProperty("needCreateNewBillCode", false);
            JsonArray a = new JsonArray();
            a.add(new JsonPrimitive("APPROVE"));
            a.add(new JsonPrimitive("21"));
            a.add(new JsonPrimitive(today()));
            a.add(JsonNull.INSTANCE);
            a.add(voJson);
            a.add(JsonNull.INSTANCE);
            a.add(JsonNull.INSTANCE);
            invoke(s, "nc.itf.uap.pf.IplatFormEntry", "processAction",
                    Arrays.asList("java.lang.String", "java.lang.String", "java.lang.String",
                            "nc.vo.pub.pf.PfUtilWorkFlowVO", "nc.vo.pub.AggregatedValueObject",
                            "java.lang.Object", "java.util.HashMap"), a);
            JsonArray chk = tableQuery(s, "po_order", new String[]{"forderstatus"},
                    "corderid='" + orderPk + "'");
            forderstatus = chk.get(0).getAsJsonArray().get(0).getAsInt();
        }
        out.addProperty("audited", forderstatus == 3);
        out.addProperty("forderstatus", forderstatus);

        // 预付款：复刻人工「辅助功能→预付款→付款录入→保存」链路（详见 Prepay.java）
        if (spec.has("prepay")) {
            JsonObject pp = spec.getAsJsonObject("prepay");
            double amount = pp.has("amount") ? pp.get("amount").getAsDouble() : 0;
            if (forderstatus != 3) throw new IllegalStateException("订单未审核，不能生成预付款");
            // 已生成付款单（fk/D3）或已送审（spzt=1）的应付单不重复提交
            JsonArray doneRows = tableQuery(s, "arap_djzb", new String[]{"vouchid", "djbh"},
                    "(djdl='fk' or spzt='1') and dr=0 and vouchid in (select vouchid from arap_djfb"
                            + " where ddhid='" + orderPk + "')");
            if (doneRows.size() > 0) {
                JsonArray d0 = doneRows.get(0).getAsJsonArray();
                out.addProperty("paySkipped", "已存在付款单");
                out.addProperty("payVouchid", d0.get(0).getAsString());
                out.addProperty("payBillCode",
                        d0.get(1).isJsonNull() ? null : d0.get(1).getAsString());
            } else {
                JsonObject pe = Prepay.entrySave(s, orderPk, corp, amount);
                out.add("payEntry", pe);
                JsonArray bills = pe.getAsJsonArray("bills");
                if (bills != null && bills.size() > 0) {
                    JsonArray b0 = bills.get(0).getAsJsonArray();
                    out.addProperty("payVouchid", b0.get(0).getAsString());
                    out.addProperty("payBillCode",
                            b0.get(1).isJsonNull() ? null : b0.get(1).getAsString());
                }
            }
        }
        out.addProperty("verified", true);
        return out;
    }

    /** 兼容旧名：见 billAction */
    public static JsonObject payAction(Session s, JsonObject spec) throws Exception {
        return billAction(s, spec);
    }

    /**
     * 对 arap 单据（付款/报销/借款等 DJZBVO）执行动作：
     * INSPECT 看 VO；DELETE/UPDATE 走 IArapBillPublic；AUDIT 系走卡片审核；
     * APPROVE 系走 UI 批审链路；其余键按平台动作 checkWorkitemOnSave+processAction。
     * billtype 缺省时按 vouchid 反查 arap_djzb.djlxbm。
     */
    public static JsonObject billAction(Session s, JsonObject spec) throws Exception {
        String vouchid = spec.has("vouchid") ? spec.get("vouchid").getAsString() : null;
        String act = spec.has("act") ? spec.get("act").getAsString() : null;
        if (vouchid == null || act == null) throw new IllegalArgumentException("需要 vouchid 和 act");
        String billtype = spec.has("billtype") ? spec.get("billtype").getAsString() : null;
        if (billtype == null) {
            JsonArray bt = tableQuery(s, "arap_djzb", new String[]{"djlxbm"},
                    "vouchid='" + vouchid + "'");
            if (bt.size() == 0 || bt.get(0).getAsJsonArray().get(0).isJsonNull())
                throw new IllegalStateException("无法按 vouchid 解析单据类型，请显式传 billtype");
            billtype = bt.get(0).getAsJsonArray().get(0).getAsString();
        }

        Object djzb = new Invoker(s).invokeRaw("nc.itf.arap.pub.IArapBillPublic", "findArapBillByPK",
                Arrays.asList("java.lang.String"), vouchid);
        Class<?> djzbCls = djzb.getClass();
        djzbCls.getMethod("setm_OldVO", djzbCls).invoke(djzb, djzb);

        // 可选：保存前写字段（fields=表头, itemFields=全部表体行）
        if (spec.has("fields") || spec.has("itemFields")) {
            Object head0 = djzbCls.getMethod("getParentVO").invoke(djzb);
            java.lang.reflect.Method setAttr = head0.getClass()
                    .getMethod("setAttributeValue", String.class, Object.class);
            if (spec.has("fields")) {
                for (java.util.Map.Entry<String, JsonElement> fe
                        : spec.getAsJsonObject("fields").entrySet()) {
                    setAttr.invoke(head0, fe.getKey(), fe.getValue().isJsonNull()
                            ? null : fe.getValue().getAsString());
                }
            }
            if (spec.has("itemFields")) {
                Object[] items = (Object[]) djzbCls.getMethod("getChildrenVO").invoke(djzb);
                if (items != null) {
                    for (Object it : items) {
                        java.lang.reflect.Method itSet = it.getClass()
                                .getMethod("setAttributeValue", String.class, Object.class);
                        for (java.util.Map.Entry<String, JsonElement> fe
                                : spec.getAsJsonObject("itemFields").entrySet()) {
                            itSet.invoke(it, fe.getKey(), fe.getValue().isJsonNull()
                                    ? null : fe.getValue().getAsString());
                        }
                    }
                }
            }
        }

        java.util.Map<String, Object> ep = new java.util.HashMap<String, Object>();
        Object ret;
        Object wfVo = null;
        if ("INSPECT".equalsIgnoreCase(act)) {
            JsonObject o = new JsonObject();
            o.add("vo", JsonCodec.toJson(djzb));
            return o;
        }
        if ("DELETE".equalsIgnoreCase(act)) {
            ret = new Invoker(s).invokeRaw("nc.itf.arap.pub.IArapBillPublic", "deleteArapBill",
                    Arrays.asList("nc.vo.ep.dj.DJZBVO"), djzb);
        } else if ("UPDATE".equalsIgnoreCase(act)) {
            // 直接走 IArapBillPublic.updateArapBill（平台 EDIT 不写表体字段）
            ret = new Invoker(s).invokeRaw("nc.itf.arap.pub.IArapBillPublic", "updateArapBill",
                    Arrays.asList("nc.vo.ep.dj.DJZBVO"), djzb);
        } else if (act.toUpperCase().startsWith("AUDIT") || act.toUpperCase().startsWith("UNAUDIT")) {
            // 卡片级审核接口（IArapBillPublic），不走平台 APPROVE 批处理
            String m = "AUDITONE".equalsIgnoreCase(act) ? "auditOneArapBill"
                    : "UNAUDITONE".equalsIgnoreCase(act) ? "unAuditOneArapBill"
                    : "AUDIT".equalsIgnoreCase(act) ? "auditArapBill" : "unAuditArapBill";
            ret = new Invoker(s).invokeRaw("nc.itf.arap.pub.IArapBillPublic", m,
                    Arrays.asList("nc.vo.ep.dj.DJZBVO"), djzb);
        } else if (act.toUpperCase().startsWith("APPROVE") || act.toUpperCase().startsWith("UNAPPROVE")) {
            // UI 审核链路（ListBatchAudit）：checkWorkFlow + processBatch，userObj=[zjjh,budget,settleList,ts]
            ep.put("reload_vo", "reload_vo");
            String actName = act + s.getUserId();
            Object head = djzbCls.getMethod("getParentVO").invoke(djzb);
            Object ts = head.getClass().getMethod("getAttributeValue", String.class).invoke(head, "ts");
            java.util.List<Object> uo = new ArrayList<Object>();
            uo.add(safeField(djzbCls, djzb, "hasZjjhCheck"));
            uo.add(safeField(djzbCls, djzb, "budgetCheck"));
            uo.add(safeMethod(djzbCls, djzb, "getSettlementAccountList"));
            uo.add(ts);
            wfVo = new Invoker(s).invokeRaw("nc.itf.uap.pf.IWorkflowMachine", "checkWorkFlow",
                    Arrays.asList("java.lang.String", "java.lang.String", "java.lang.String",
                            "nc.vo.pub.AggregatedValueObject", "java.util.HashMap"),
                    actName, billtype, today(), djzb, ep);
            // checkWorkFlow 服务端可能已改单据 ts：重新加载拿最新 ts 再批处理
            djzb = new Invoker(s).invokeRaw("nc.itf.arap.pub.IArapBillPublic", "findArapBillByPK",
                    Arrays.asList("java.lang.String"), vouchid);
            djzbCls.getMethod("setm_OldVO", djzbCls).invoke(djzb, djzb);
            head = djzbCls.getMethod("getParentVO").invoke(djzb);
            uo.set(3, head.getClass().getMethod("getAttributeValue", String.class).invoke(head, "ts"));
            // 头单 VO（ListBatchAudit 只塞 parentVO）
            Object headOnly = djzbCls.newInstance();
            djzbCls.getMethod("setParentVO", Class.forName("nc.vo.pub.CircularlyAccessibleValueObject"))
                    .invoke(headOnly, head);
            Object voAry = java.lang.reflect.Array.newInstance(djzbCls, 1);
            java.lang.reflect.Array.set(voAry, 0, headOnly);
            Object uoAry = java.lang.reflect.Array.newInstance(Object.class, 1);
            java.lang.reflect.Array.set(uoAry, 0, uo);
            ret = new Invoker(s).invokeRaw("nc.itf.uap.pf.IplatFormEntry", "processBatch",
                    Arrays.asList("java.lang.String", "java.lang.String", "java.lang.String",
                            "nc.vo.pub.pf.PfUtilWorkFlowVO", "[Lnc.vo.pub.AggregatedValueObject;",
                            "[Ljava.lang.Object;", "java.util.HashMap"),
                    actName, billtype, today(), wfVo, voAry, uoAry, ep);
        } else {
            wfVo = new Invoker(s).invokeRaw("nc.itf.uap.pf.IWorkflowMachine", "checkWorkitemOnSave",
                    Arrays.asList("java.lang.String", "java.lang.String", "java.lang.String",
                            "nc.vo.pub.AggregatedValueObject", "java.util.HashMap"),
                    act, billtype, today(), djzb, ep);
            ret = new Invoker(s).invokeRaw("nc.itf.uap.pf.IplatFormEntry", "processAction",
                    Arrays.asList("java.lang.String", "java.lang.String", "java.lang.String",
                            "nc.vo.pub.pf.PfUtilWorkFlowVO", "nc.vo.pub.AggregatedValueObject",
                            "java.lang.Object", "java.util.HashMap"),
                    act, billtype, today(), wfVo, djzb, djzb, ep);
        }

        JsonObject out = new JsonObject();
        out.addProperty("act", act);
        out.addProperty("wfVoPresent", wfVo != null);
        out.add("ret", JsonCodec.toJson(ret));
        return out;
    }

    private static Object safeField(Class<?> c, Object o, String name) {
        try {
            java.lang.reflect.Field f = c.getField(name);
            return f.get(o);
        } catch (Exception e) {
            return null;
        }
    }

    private static Object safeMethod(Class<?> c, Object o, String name) {
        try {
            return c.getMethod(name).invoke(o);
        } catch (Exception e) {
            return null;
        }
    }

    /** CHGZ2TO21 转换：ManageVO -> OrderVO JSON，补 OID 后 saveBatch，返回 corderid */
    private static String createOrderFromCt(Session s, JsonObject spec, JsonObject ctJson,
                                            String ctPk, String corp) throws Exception {
        JsonObject ch = ctJson.getAsJsonObject("parent");
        JsonArray citems = ctJson.has("children") ? ctJson.getAsJsonArray("children") : new JsonArray();
        if (citems.size() == 0) throw new IllegalStateException("合同无表体行，无法生成订单");

        String bizName = optStr(spec, "bizTypeName");
        String bizField = Profile.get("contract.bizTypeField");
        if (bizField == null) bizField = "def8";
        if (bizName == null && ch.has(bizField) && !ch.get(bizField).isJsonNull())
            bizName = ch.get(bizField).getAsString();
        if (bizName == null) throw new IllegalArgumentException(
                "无法确定业务类型(合同 " + bizField + " 为空)，请传 bizTypeName");
        String cbiztype = resolveBizType(s, corp, bizName);

        String srcBillType = Profile.get("contract.sourceBillType") != null
                ? Profile.get("contract.sourceBillType") : "Z2";
        String custid = ch.get("custid").getAsString();
        JsonArray cu = tableQuery(s, "bd_cumandoc",
                new String[]{"pk_cubasdoc", "pk_cusmandoc2"}, "pk_cumandoc='" + custid + "'");
        String cvendorbaseid = cu.size() > 0 && !cu.get(0).getAsJsonArray().get(0).isJsonNull()
                ? cu.get(0).getAsJsonArray().get(0).getAsString() : null;
        String cgiveinv = cu.size() > 0 && !cu.get(0).getAsJsonArray().get(1).isJsonNull()
                ? cu.get(0).getAsJsonArray().get(1).getAsString() : custid;

        String headPk = s.genOid(corp);
        JsonObject head = new JsonObject();
        head.addProperty("corderid", headPk);
        head.addProperty("pk_corp", corp);
        head.addProperty("forderstatus", 0);
        head.addProperty("dr", 0);
        head.addProperty("nversion", 1);
        head.addProperty("bislatest", true);
        head.addProperty("bisreplenish", false);
        head.addProperty("breturn", false);
        head.addProperty("idiscounttaxtype", 1);
        String dorderdate = optStr(spec.getAsJsonObject("head"), "dorderdate");
        if (dorderdate == null) dorderdate = today();
        head.addProperty("dorderdate", dorderdate);
        head.addProperty("caccountyear", dorderdate.substring(0, 4));
        head.addProperty("coperator", s.getUserId());
        head.addProperty("cuserid", s.getUserId());
        copyIf(ch, head, "personnelid", "cemployeeid");
        copyIf(ch, head, "depid", "cdeptid");
        copyIf(ch, head, "currid", "ccurrencytypeid");
        copyIf(ch, head, "ctermprotocolid", "ctermprotocolid");
        copyIf(ch, head, "deliaddr", "cdeliveraddress");
        copyIf(ch, head, "projectid", "cprojectid");
        head.addProperty("cvendormangid", custid);
        if (cvendorbaseid != null) head.addProperty("cvendorbaseid", cvendorbaseid);
        head.addProperty("cgiveinvoicevendor", cgiveinv);
        head.addProperty("cbiztype", cbiztype);
        // 业务类型名回写到的订单自定义项（如 vdef8）：由 profile order.bizTypeNameField 指定，不配则不写
        String bizNameField = Profile.get("order.bizTypeNameField");
        if (bizNameField != null) head.addProperty(bizNameField, bizName);
        String purOrg = Profile.get("order.purchaseOrgPk");
        if (purOrg != null && !head.has("cpurorganization"))
            head.addProperty("cpurorganization", purOrg);
        for (int i = 1; i <= 20; i++) {
            copyIf(ch, head, "def" + i, "vdef" + i);
            copyIf(ch, head, "pk_defdoc" + i, "pk_defdoc" + i);
        }
        if (spec.has("head")) {
            for (java.util.Map.Entry<String, JsonElement> e : spec.getAsJsonObject("head").entrySet()) {
                head.add(e.getKey(), e.getValue());
            }
        }
        head.addProperty("status", VO_NEW);

        JsonArray specItems = spec.has("items") ? spec.getAsJsonArray("items") : new JsonArray();
        JsonArray children = new JsonArray();
        double totalNum = 0, totalMny = 0;
        for (int i = 0; i < citems.size(); i++) {
            JsonObject ci = citems.get(i).getAsJsonObject();
            String bid = ci.has("pk_ct_manage_b") && !ci.get("pk_ct_manage_b").isJsonNull()
                    ? ci.get("pk_ct_manage_b").getAsString() : null;
            JsonObject it = new JsonObject();
            it.addProperty("corder_bid", s.genOid(corp));
            it.addProperty("corderid", headPk);
            it.addProperty("pk_corp", corp);
            it.addProperty("crowno", optStr(ci, "crowno") != null ? ci.get("crowno").getAsString()
                    : String.valueOf((i + 1) * 10));
            copyIf(ci, it, "invid", "cmangid");
            copyIf(ci, it, "invid", "cinventoryid");
            copyIf(ci, it, "measid", "measid");
            if (it.has("cinventoryid")) {
                JsonArray inv = tableQuery(s, "bd_invmandoc", new String[]{"pk_invbasdoc"},
                        "pk_invmandoc='" + it.get("cinventoryid").getAsString() + "'");
                if (inv.size() > 0 && !inv.get(0).getAsJsonArray().get(0).isJsonNull()) {
                    it.addProperty("cbaseid", inv.get(0).getAsJsonArray().get(0).getAsString());
                }
            }
            copyIf(ch, it, "currid", "ccurrencytypeid");
            if (ch.has("currrate") && !ch.get("currrate").isJsonNull())
                it.add("nexchangeotobrate", ch.get("currrate"));
            else it.addProperty("nexchangeotobrate", 1);
            double ordnum = ci.has("ordnum") && !ci.get("ordnum").isJsonNull() ? ci.get("ordnum").getAsDouble() : 0;
            double amount = ci.has("amount") && !ci.get("amount").isJsonNull() ? ci.get("amount").getAsDouble() : 0;
            it.addProperty("nordernum", Math.max(0, amount - ordnum));
            copyIf(ci, it, "oriprice", "noriginalcurprice");
            copyIf(ci, it, "oriprice", "noriginalnetprice");
            copyIf(ci, it, "orisum", "noriginalcurmny");
            copyIf(ci, it, "orisum", "nmoney");
            copyIf(ci, it, "taxration", "ntaxrate");
            copyIf(ci, it, "oritaxmny", "noriginaltaxmny");
            copyIf(ci, it, "oritaxmny", "ntaxmny");
            copyIf(ci, it, "oritaxsummny", "noriginaltaxpricemny");
            copyIf(ci, it, "oritaxsummny", "ntaxpricemny");
            copyIf(ci, it, "oritaxprice", "norgtaxprice");
            copyIf(ci, it, "oritaxprice", "norgnettaxprice");
            copyIf(ci, it, "transmodeid", "ctransmodeid_ct");
            copyIf(ci, it, "ctranspmodeid", "ctransmodeid_ct");
            copyIf(ci, it, "astmeasid", "cassistunit");
            copyIf(ci, it, "vbatchcode", "vproducenum");
            copyIf(ci, it, "transrate", "nconvertrate");
            it.addProperty("ndiscountrate", 100);
            it.addProperty("idiscounttaxtype", 1);
            it.addProperty("forderrowstatus", 0);
            it.addProperty("iisactive", 0);
            it.addProperty("iisreplenish", 0);
            it.addProperty("breceiveplan", false);
            it.addProperty("blargess", false);
            it.addProperty("dr", 0);
            it.addProperty("status", VO_NEW);
            it.addProperty("pk_reqcorp", corp);
            it.addProperty("pk_arrvcorp", corp);
            it.addProperty("pk_invoicecorp", corp);
            it.addProperty("pk_upsrccorp", corp);
            it.addProperty("csourcebillid", ctPk);
            it.addProperty("csourcebilltype", srcBillType);
            it.addProperty("cupsourcebillid", ctPk);
            it.addProperty("cupsourcebilltype", srcBillType);
            it.addProperty("ccontractid", ctPk);
            if (bid != null) {
                it.addProperty("csourcerowid", bid);
                it.addProperty("cupsourcebillrowid", bid);
                it.addProperty("ccontractrowid", bid);
            }
            // 表头自定义项向表体行透传：profile order.propagateHeadFields 指定字段名单（如 ["vdef4"]）
            com.google.gson.JsonArray prop = Profile.getArr("order.propagateHeadFields");
            if (prop != null) {
                for (JsonElement pf : prop) {
                    String f = pf.getAsString();
                    if (head.has(f)) it.add(f, head.get(f));
                }
            }
            if (bizNameField != null) it.addProperty(bizNameField, bizName);
            for (int n = 1; n <= 20; n++) {
                copyIf(ci, it, "def" + n, "vdef" + n);
                copyIf(ci, it, "pk_defdoc" + n, "pk_defdoc" + n);
            }
            // 行覆盖（按 pk_ct_manage_b 或 crowno 匹配）
            for (JsonElement oe : specItems) {
                JsonObject ov = oe.getAsJsonObject();
                if ((bid != null && bid.equals(optStr(ov, "pk_ct_manage_b")))
                        || it.get("crowno").getAsString().equals(optStr(ov, "crowno"))) {
                    for (java.util.Map.Entry<String, JsonElement> e : ov.entrySet()) {
                        if (!"pk_ct_manage_b".equals(e.getKey())) it.add(e.getKey(), e.getValue());
                    }
                }
            }
            String arrvsto = Profile.get("order.arriveStoreOrg." + corp);
            if (it.has("pk_arrvstoorg") == false && arrvsto != null)
                it.addProperty("pk_arrvstoorg", arrvsto);
            if (it.has("cwarehouseid") == false) {
                String wh = resolveWarehouse(s, spec, corp,
                        it.has("cmangid") && !it.get("cmangid").isJsonNull()
                                ? it.get("cmangid").getAsString() : null);
                if (wh != null) it.addProperty("cwarehouseid", wh);
            }
            totalNum += it.get("nordernum").getAsDouble();
            totalMny += it.has("ntaxpricemny") ? it.get("ntaxpricemny").getAsDouble() : 0;
            children.add(it);
        }
        head.addProperty("ntotalnum", totalNum);
        head.addProperty("ntotalmny", totalMny);

        JsonObject vo = new JsonObject();
        vo.addProperty("_class", "nc.vo.po.OrderVO");
        vo.addProperty("status", VO_NEW);
        vo.addProperty("needCreateNewBillCode", true);
        vo.addProperty("groupPkCorp", s.getPkCorp());
        vo.addProperty("pkBusinessType", cbiztype);
        vo.add("parent", head);
        vo.add("children", children);

        JsonArray args = new JsonArray();
        JsonArray arr = new JsonArray();
        arr.add(vo);
        args.add(arr);
        invoke(s, "nc.itf.po.IOrder", "saveBatch", Arrays.asList("[Lnc.vo.po.OrderVO;"), args);
        return headPk;
    }

    /** 加载合同全量 VO（头+体） */
    private static JsonObject loadCtJson(Session s, String corp, String where, String ctCode) throws Exception {
        Object[] mvs = (Object[]) new Invoker(s).invokeRaw(
                "nc.itf.ct.pub.IContractBaseQuery", "queryAllHeadData",
                Arrays.asList("java.lang.String", "java.lang.String"), corp, where);
        if (mvs == null || mvs.length == 0) throw new IllegalStateException("合同头查询为空: " + ctCode);
        Object full = new Invoker(s).invokeRaw(
                "nc.itf.ct.pub.IContractBaseQuery", "queryAllBodyData",
                Arrays.asList("nc.vo.ct.pub.ManageVO"), mvs[0]);
        return JsonCodec.toJson(full).getAsJsonObject();
    }

    /**
     * 从合同反查现存订单的 corderid：候选行可能含已废弃单（DISCARD 后 dr=1 逻辑删除残留），
     * 逐个查头表 forderstatus+dr，取最后一个有效（dr=0 且 forderstatus!=1）的
     */
    private static String pickLiveOrder(Session s, String ctPk) throws Exception {
        JsonArray rows = tableQuery(s, "po_order_b", new String[]{"corderid"},
                "ccontractid='" + ctPk + "'");
        String pick = null;
        for (JsonElement e : rows) {
            String pk = e.getAsJsonArray().get(0).getAsString();
            JsonArray h = tableQuery(s, "po_order", new String[]{"forderstatus", "dr"},
                    "corderid='" + pk + "'");
            if (h.size() == 0) continue; // 物理删除的残留行引用
            JsonArray row = h.get(0).getAsJsonArray();
            boolean deleted = !row.get(0).isJsonNull() && row.get(0).getAsInt() == 1
                    || !row.get(1).isJsonNull() && row.get(1).getAsInt() != 0;
            if (!deleted) pick = pk;
        }
        return pick;
    }

    /** 订单行仓库解析：① spec.head.cwarehouseid ② spec.warehouse(编码或名称) ③ 同存货最近一单先例 */
    private static String resolveWarehouse(Session s, JsonObject spec, String corp, String invPk) throws Exception {
        String wh = optStr(spec.has("head") ? spec.getAsJsonObject("head") : null, "cwarehouseid");
        if (wh != null) return wh;
        String wname = optStr(spec, "warehouse");
        if (wname != null) {
            JsonArray rows = tableQuery(s, "bd_stordoc", new String[]{"pk_stordoc"},
                    "pk_corp='" + corp + "' and (storcode='" + wname.replace("'", "''")
                            + "' or storname='" + wname.replace("'", "''") + "')");
            if (rows.size() == 0) throw new IllegalStateException("仓库不存在: " + wname + " (corp=" + corp + ")");
            return rows.get(0).getAsJsonArray().get(0).getAsString();
        }
        return invPk != null ? precedentWarehouse(s, corp, invPk) : null;
    }

    /**
     * 废弃自由态采购订单：{"orderPk"|"vordercode":...} -> {"discarded":true,"forderstatus":1}
     */
    public static JsonObject discardPurchaseOrder(Session s, JsonObject spec) throws Exception {
        String pk = optStr(spec, "orderPk");
        if (pk == null) {
            String code = optStr(spec, "vordercode");
            if (code == null) throw new IllegalArgumentException("需要 orderPk 或 vordercode");
            JsonArray r = tableQuery(s, "po_order", new String[]{"corderid"},
                    "vordercode='" + code.replace("'", "''") + "'");
            if (r.size() == 0) throw new IllegalStateException("订单不存在: " + code);
            pk = r.get(0).getAsJsonArray().get(0).getAsString();
        }
        JsonArray h = tableQuery(s, "po_order", new String[]{"forderstatus", "dr"},
                "corderid='" + pk + "'");
        if (h.size() == 0) throw new IllegalStateException("订单不存在: " + pk);
        JsonArray hr = h.get(0).getAsJsonArray();
        if (!hr.get(1).isJsonNull() && hr.get(1).getAsInt() != 0)
            throw new IllegalStateException("订单已逻辑删除: " + pk);
        int st = hr.get(0).getAsInt();
        if (st != 0 && st != 4) throw new IllegalStateException("仅自由态(0)/审核失败(4)订单可废弃，当前=" + st);
        JsonElement vo = JsonCodec.toJson(new Invoker(s).invokeRaw(
                "nc.itf.po.IOrder", "queryOrderVOByKey", Arrays.asList("java.lang.String"), pk));
        if (vo == null || vo.isJsonNull())
            throw new IllegalStateException("订单不可读取(可能已删除): " + pk);
        JsonArray bids = tableQuery(s, "po_order_b", new String[]{"corder_bid"},
                "corderid='" + pk + "'");
        for (JsonElement e : bids) releasePkLock(s, e.getAsJsonArray().get(0).getAsString());
        releasePkLock(s, pk);
        discardOrder(s, vo);
        JsonArray chk = tableQuery(s, "po_order", new String[]{"forderstatus"},
                "corderid='" + pk + "'");
        JsonObject out = new JsonObject();
        out.addProperty("orderPk", pk);
        out.addProperty("forderstatus", chk.size() > 0 ? chk.get(0).getAsJsonArray().get(0).getAsInt() : -9);
        out.addProperty("discarded", chk.size() == 0 || chk.get(0).getAsJsonArray().get(0).getAsInt() == 1);
        return out;
    }

    /** 废弃订单：平台 DISCARD 动作，VO 置 DELETED；服务端要求具体 OrderVO[] 类型，须 invokeRaw */
    private static void discardOrder(Session s, JsonElement voJson) throws Exception {
        Class<?> orderCls = Class.forName("nc.vo.po.OrderVO");
        // UI 废弃前会填操作日志 VO，缺失时服务端 NPE
        if (voJson.isJsonObject()) {
            JsonObject v = voJson.getAsJsonObject();
            if (!v.has("operatelogVO") || v.get("operatelogVO").isJsonNull()) {
                JsonObject log = new JsonObject();
                log.addProperty("pkcorp", optStr(v.getAsJsonObject("parent"), "pk_corp"));
                log.addProperty("enterip", localIp());
                v.add("operatelogVO", log);
            }
        }
        Object vo = JsonCodec.toArg(orderCls, voJson);
        vo.getClass().getMethod("setStatus", int.class).invoke(vo, 3);
        // OrderVO.setStatus(3) 只联动表体，表头须显式置 DELETED，否则服务端当无变化跳过
        Object head = vo.getClass().getMethod("getHeadVO").invoke(vo);
        head.getClass().getMethod("setStatus", int.class).invoke(head, 3);
        Object arr = java.lang.reflect.Array.newInstance(orderCls, 1);
        java.lang.reflect.Array.set(arr, 0, vo);
        new Invoker(s).invokeRaw("nc.itf.uap.pf.IplatFormEntry", "processBatch",
                Arrays.asList("java.lang.String", "java.lang.String", "java.lang.String",
                        "nc.vo.pub.pf.PfUtilWorkFlowVO", "[Lnc.vo.pub.AggregatedValueObject;",
                        "[Ljava.lang.Object;", "java.util.HashMap"),
                "DISCARD", "21", today(), null, arr, null, null);
    }

    /** 释放本账号在指定 pk 上的单据锁（忽略失败） */
    private static void releasePkLock(Session s, String pk) {
        if (pk == null) return;
        try {
            JsonArray a = new JsonArray();
            a.add(new JsonPrimitive(pk));
            a.add(new JsonPrimitive(s.getUserId()));
            a.add(JsonNull.INSTANCE);
            invoke(s, "nc.itf.uap.IPKLockBS", "releaseLock_RequiresNew",
                    Arrays.asList("java.lang.String", "java.lang.String", "java.lang.String"), a);
        } catch (Exception ignore) {
        }
    }

    /** 同公司同存货最近一次订单行上的仓库（先例取值） */
    private static String precedentWarehouse(Session s, String corp, String invPk) throws Exception {
        JsonArray rows = tableQuery(s, "po_order_b", new String[]{"corderid", "cwarehouseid"},
                "cmangid='" + invPk + "' and cwarehouseid is not null");
        if (rows.size() == 0) return null;
        StringBuilder in = new StringBuilder();
        for (JsonElement e : rows) {
            if (in.length() > 0) in.append(',');
            in.append('\'').append(e.getAsJsonArray().get(0).getAsString()).append('\'');
        }
        JsonArray heads = tableQuery(s, "po_order", new String[]{"corderid", "dorderdate"},
                "pk_corp='" + corp + "' and corderid in (" + in + ")");
        String best = null, bestDate = null;
        for (JsonElement e : heads) {
            String d = e.getAsJsonArray().get(1).isJsonNull() ? "" : e.getAsJsonArray().get(1).getAsString();
            if (bestDate == null || d.compareTo(bestDate) > 0) {
                bestDate = d;
                best = e.getAsJsonArray().get(0).getAsString();
            }
        }
        if (best == null) return null;
        for (JsonElement e : rows) {
            if (e.getAsJsonArray().get(0).getAsString().equals(best))
                return e.getAsJsonArray().get(1).getAsString();
        }
        return null;
    }

    private static void copyIf(JsonObject from, JsonObject to, String fk, String tk) {
        if (from.has(fk) && !from.get(fk).isJsonNull()) to.add(tk, from.get(fk));
    }

    private static String optStr(JsonObject o, String k) {
        return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }

    /** 通用表查询：返回行数组的 JsonArray */
    public static JsonArray tableQuery(Session s, String table, String[] cols, String where) throws Exception {
        JsonArray colsArr = new JsonArray();
        for (String c : cols) colsArr.add(new JsonPrimitive(c));
        JsonArray args = new JsonArray();
        args.add(new JsonPrimitive(table));
        args.add(colsArr);
        args.add(new JsonPrimitive(where));
        Object res = invoke(s, "nc.itf.pu.pub.IPub", "queryResultsFromAnyTable",
                Arrays.asList("java.lang.String", "[Ljava.lang.String;", "java.lang.String"), args);
        JsonElement rows = JsonCodec.toJson(res);
        return rows != null && rows.isJsonArray() ? rows.getAsJsonArray() : new JsonArray();
    }

    /** bd_busitype: 按公司+名称解析业务类型 PK */
    private static String resolveBizType(Session s, String corp, String businame) throws Exception {
        JsonArray rows = tableQuery(s, "bd_busitype", new String[]{"pk_busitype"},
                "pk_corp='" + corp + "' and businame='" + businame.replace("'", "''") + "'");
        if (rows.size() == 0) throw new IllegalStateException("公司 " + corp + " 无业务类型: " + businame);
        return rows.get(0).getAsJsonArray().get(0).getAsString();
    }

    // ---------- 内部 ----------

    /** 回查验证：按唯一编码查行，返回 {pk,code,verified}；查不到抛错 */
    private static JsonObject verify(String table, String keyCol, String key, Session s,
                                     String[] cols, String pkCol, String codeCol) throws Exception {
        JsonArray colsArr = new JsonArray();
        for (String c : cols) colsArr.add(new JsonPrimitive(c));
        JsonArray args = new JsonArray();
        args.add(new JsonPrimitive(table));
        args.add(colsArr);
        args.add(new JsonPrimitive(keyCol + "='" + key.replace("'", "''") + "'"));
        Object res = invoke(s, "nc.itf.pu.pub.IPub", "queryResultsFromAnyTable",
                Arrays.asList("java.lang.String", "[Ljava.lang.String;", "java.lang.String"), args);
        JsonElement rows = JsonCodec.toJson(res);
        JsonObject out = new JsonObject();
        out.addProperty("code", key);
        if (rows != null && rows.isJsonArray() && rows.getAsJsonArray().size() > 0) {
            JsonArray row = rows.getAsJsonArray().get(0).getAsJsonArray();
            int pkIdx = -1, codeIdx = -1;
            for (int i = 0; i < cols.length; i++) {
                if (cols[i].equals(pkCol)) pkIdx = i;
                if (cols[i].equals(codeCol)) codeIdx = i;
            }
            if (pkIdx >= 0 && !row.get(pkIdx).isJsonNull()) {
                out.addProperty("pk", row.get(pkIdx).getAsString());
            }
            if (codeIdx >= 0 && !row.get(codeIdx).isJsonNull()) {
                out.addProperty("code", row.get(codeIdx).getAsString());
            }
            out.addProperty("verified", true);
        } else {
            out.addProperty("verified", false);
            throw new IllegalStateException("写入后回查失败: " + table + "." + keyCol + "=" + key);
        }
        return out;
    }

    private static Object invoke(Session s, String itf, String method,
                                 List<String> argTypes, JsonArray args) throws Exception {
        return new Invoker(s).invoke(itf, method, new ArrayList<String>(argTypes), args);
    }

    /**
     * 服务端单据号规则取号：IBillcodeUiService.getBillCode(billTypeCode, pk_corp, customBillCode, objVO)。
     * customBillCode 传 null 才走规则；objVO 用「规则对象名」作 key（如「公司」=pk_corp），缺了会渲染成 **。
     */
    private static String fetchBillCode(Session s, String billType, String corp) throws Exception {
        JsonObject objVO = new JsonObject();
        objVO.addProperty("_class", "nc.vo.pub.billcodemanage.BillCodeObjValueVO");
        objVO.addProperty("pk_billtypecode", billType);
        objVO.addProperty("pk_corp", corp);
        objVO.addProperty("公司", corp);
        objVO.addProperty("billdate", today());
        JsonArray args = new JsonArray();
        args.add(new JsonPrimitive(billType));
        args.add(new JsonPrimitive(corp));
        args.add(JsonNull.INSTANCE);
        args.add(objVO);
        Object res = invoke(s, "nc.itf.uap.sfapp.IBillcodeUiService", "getBillCode",
                Arrays.asList("java.lang.String", "java.lang.String", "java.lang.String",
                        "nc.vo.pub.billcodemanage.BillCodeObjValueVO"), args);
        JsonElement je = JsonCodec.toJson(res);
        if (je == null || !je.isJsonPrimitive()) throw new IllegalStateException("取号失败: " + je);
        return je.getAsString();
    }

    /** 合同类型 -> 存货控制方式(ninvctlstyle) */
    private static Integer invControlType(Session s, String pkCtType) throws Exception {
        JsonArray cols = new JsonArray();
        cols.add(new JsonPrimitive("ninvctlstyle"));
        JsonArray args = new JsonArray();
        args.add(new JsonPrimitive("ct_type"));
        args.add(cols);
        args.add(new JsonPrimitive("pk_ct_type='" + pkCtType + "'"));
        Object res = invoke(s, "nc.itf.pu.pub.IPub", "queryResultsFromAnyTable",
                Arrays.asList("java.lang.String", "[Ljava.lang.String;", "java.lang.String"), args);
        JsonElement rows = JsonCodec.toJson(res);
        if (rows != null && rows.isJsonArray() && rows.getAsJsonArray().size() > 0) {
            JsonElement v = rows.getAsJsonArray().get(0).getAsJsonArray().get(0);
            if (!v.isJsonNull()) return v.getAsInt();
        }
        return null;
    }

    private static JsonObject req(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonObject()) {
            throw new IllegalArgumentException("缺少对象字段: " + key);
        }
        return o.getAsJsonObject(key);
    }

    private static void putDefault(JsonObject o, String k, String v) {
        if (!o.has(k) || o.get(k).isJsonNull()) o.addProperty(k, v);
    }

    private static void putDefault(JsonObject o, String k, int v) {
        if (!o.has(k) || o.get(k).isJsonNull()) o.addProperty(k, v);
    }

    private static void putDefault(JsonObject o, String k, boolean v) {
        if (!o.has(k) || o.get(k).isJsonNull()) o.addProperty(k, v);
    }

    /** 业务日期：北京时间当天 */
    private static String today() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd");
        f.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        return f.format(new Date());
    }

    private static String autoCode(String prefix) {
        SimpleDateFormat f = new SimpleDateFormat("yyMMddHHmmss");
        f.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        return prefix + f.format(new Date());
    }

    private static String localIp() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }
}
