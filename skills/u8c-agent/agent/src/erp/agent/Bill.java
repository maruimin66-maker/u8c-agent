package erp.agent;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import nc.vo.pub.AggregatedValueObject;

/**
 * 通用探测与单据执行动作：
 *   query     —— 任意表只读查询（queryResultsFromAnyTable 封装）
 *   billTypes —— 探测本环境启用的单据类型（多候选元数据表自适应）
 *   billSpec  —— 拉取先例单据的全字段模板（arap 走 VO 全量，其他走表行）
 *   billSave  —— 由 JSON 构造聚合 VO 并走平台 SAVE（dryRun 只校验不落库）
 *   billAction—— 对既有单据做平台/卡片动作（vouchid/billType 自动解析）
 */
public final class Bill {

    private static final int VO_NEW = 2; // nc.vo.pub.VOStatus.NEW
    private static final String ARAP_VO = "nc.vo.ep.dj.DJZBVO";

    private Bill() {
    }

    // ---------- query：任意表查询 ----------

    /**
     * {"action":"query","table":"bd_measdoc","cols":["pk_measdoc","measname"],
     *  "where":"measname='次'","maxRows":50}
     * 返回 {"cols":[...],"rows":[[...]]}
     */
    public static JsonObject query(Session s, JsonObject spec) throws Exception {
        String table = spec.has("table") ? spec.get("table").getAsString() : null;
        if (table == null || !table.matches("[A-Za-z_][A-Za-z0-9_\\.]*"))
            throw new IllegalArgumentException("需要合法 table 名");
        JsonArray cols;
        if (spec.has("cols") && spec.get("cols").isJsonArray()) {
            cols = spec.getAsJsonArray("cols");
        } else {
            throw new IllegalArgumentException("需要 cols 数组（queryResultsFromAnyTable 不支持 *）");
        }
        for (JsonElement c : cols) {
            if (!c.getAsString().matches("[A-Za-z_][A-Za-z0-9_\\.]*"))
                throw new IllegalArgumentException("非法列名: " + c.getAsString());
        }
        String where = spec.has("where") && !spec.get("where").isJsonNull()
                ? spec.get("where").getAsString() : "1=1";
        JsonArray rows = Docs.tableQuery(s, table, toStrArr(cols), where);
        int max = spec.has("maxRows") ? spec.get("maxRows").getAsInt() : 200;
        JsonArray out = new JsonArray();
        for (int i = 0; i < rows.size() && i < max; i++) out.add(rows.get(i));
        JsonObject r = new JsonObject();
        r.add("cols", cols);
        r.add("rows", out);
        r.addProperty("rowCount", out.size());
        r.addProperty("truncated", rows.size() > max);
        return r;
    }

    // ---------- billTypes：单据类型探测 ----------

    private static final String[][] BILLTYPE_TABLES = {
            // {table, codeCol, nameCol}
            {"bd_billtype", "pk_billtypecode", "billtypename"},
            {"bd_billtype", "billtypecode", "billtypename"},
            {"pub_billtype", "pk_billtypecode", "billtypename"},
            {"pub_billtype", "billtypecode", "billtypename"},
            {"sm_billtype", "pk_billtypecode", "billtypename"},
            {"sm_billtype", "billtypecode", "billtypename"},
    };

    /**
     * {"action":"billTypes","where":"可选附加条件","maxRows":200}
     * 依候选元数据表逐个探测，首个可查询的表返回其单据类型清单。
     */
    public static JsonObject billTypes(Session s, JsonObject spec) throws Exception {
        String extra = spec.has("where") && !spec.get("where").isJsonNull()
                ? " and (" + spec.get("where").getAsString() + ")" : "";
        StringBuilder tried = new StringBuilder();
        for (String[] cand : BILLTYPE_TABLES) {
            try {
                JsonArray rows = Docs.tableQuery(s, cand[0],
                        new String[]{cand[1], cand[2]}, "1=1" + extra);
                if (rows.size() == 0) continue;
                int max = spec.has("maxRows") ? spec.get("maxRows").getAsInt() : 300;
                JsonObject out = new JsonObject();
                out.addProperty("metaTable", cand[0]);
                JsonArray colsArr = new JsonArray();
                colsArr.add(new JsonPrimitive(cand[1]));
                colsArr.add(new JsonPrimitive(cand[2]));
                out.add("cols", colsArr);
                JsonArray list = new JsonArray();
                for (int i = 0; i < rows.size() && i < max; i++) list.add(rows.get(i));
                out.add("rows", list);
                out.addProperty("rowCount", list.size());
                return out;
            } catch (Exception e) {
                if (tried.length() > 0) tried.append(", ");
                tried.append(cand[0]).append('(').append(cand[1]).append(')');
            }
        }
        throw new IllegalStateException("候选单据类型元数据表均不可查: " + tried
                + "。可用 {\"action\":\"describe\",\"service\":\"nc.itf.pu.pub.IPub\"} 或 query 动作自行定位");
    }

    // ---------- billSpec：先例单据字段模板 ----------

    /**
     * arap 路径: {"action":"billSpec","billType":"D3"|"djbh":"FKxxx","corp":"可选"}
     *   取该单据类型 dr=0 最新一张，findArapBillByPK 拉全量 VO（含全部字段名与先例值）。
     * 通用表路径: {"action":"billSpec","table":"po_order","where":"...",
     *             "bodyTable":"po_order_b","bodyFk":"corderid","headPk":"corderid"}
     */
    public static JsonObject billSpec(Session s, JsonObject spec) throws Exception {
        if (spec.has("table")) return tableSpec(s, spec);
        return arapSpec(s, spec);
    }

    private static JsonObject arapSpec(Session s, JsonObject spec) throws Exception {
        String where;
        if (spec.has("djbh")) {
            where = "djbh='" + spec.get("djbh").getAsString().replace("'", "''") + "'";
        } else if (spec.has("billType")) {
            where = "djlxbm='" + spec.get("billType").getAsString().replace("'", "''")
                    + "' and dr=0";
        } else {
            throw new IllegalArgumentException("需要 billType(djlxbm) 或 djbh");
        }
        if (spec.has("corp")) {
            where += " and pk_corp='" + spec.get("corp").getAsString().replace("'", "''") + "'";
        }
        JsonArray rows = Docs.tableQuery(s, "arap_djzb",
                new String[]{"vouchid", "djbh", "djlxbm", "djdl", "spzt", "ts"},
                where + " order by ts desc");
        if (rows.size() == 0)
            throw new IllegalStateException("未找到先例单据: " + where);
        JsonArray r0 = rows.get(0).getAsJsonArray();
        String vouchid = r0.get(0).getAsString();
        Object vo = new Invoker(s).invokeRaw("nc.itf.arap.pub.IArapBillPublic",
                "findArapBillByPK", Arrays.asList("java.lang.String"), vouchid);
        JsonObject out = new JsonObject();
        out.addProperty("kind", "arap");
        out.addProperty("vouchid", vouchid);
        out.addProperty("djbh", r0.get(1).isJsonNull() ? null : r0.get(1).getAsString());
        out.addProperty("djlxbm", r0.get(2).isJsonNull() ? null : r0.get(2).getAsString());
        out.addProperty("djdl", r0.get(3).isJsonNull() ? null : r0.get(3).getAsString());
        out.addProperty("spzt", r0.get(4).isJsonNull() ? null : r0.get(4).getAsString());
        out.addProperty("precedents", rows.size());
        out.add("vo", JsonCodec.toJson(vo));
        return out;
    }

    private static JsonObject tableSpec(Session s, JsonObject spec) throws Exception {
        String table = spec.get("table").getAsString();
        if (!table.matches("[A-Za-z_][A-Za-z0-9_\\.]*"))
            throw new IllegalArgumentException("非法 table 名");
        String where = spec.has("where") ? spec.get("where").getAsString() : "1=1";
        // 头表全列未知时无法 * 查；要求调用方给 cols，或用 information_schema 思路由调用方自行 query
        if (!spec.has("cols"))
            throw new IllegalArgumentException("通用表路径需要显式 cols（可用 query 动作先探列）");
        JsonObject q = new JsonObject();
        q.addProperty("table", table);
        q.add("cols", spec.get("cols"));
        q.addProperty("where", where);
        q.addProperty("maxRows", spec.has("maxRows") ? spec.get("maxRows").getAsInt() : 5);
        JsonObject head = query(s, q);
        JsonObject out = new JsonObject();
        out.addProperty("kind", "table");
        out.add("head", head);
        if (spec.has("bodyTable") && spec.has("headPk") && spec.has("bodyFk")
                && head.getAsJsonArray("rows").size() > 0) {
            JsonArray cols = spec.getAsJsonArray("cols");
            int pkIdx = -1;
            for (int i = 0; i < cols.size(); i++) {
                if (spec.get("headPk").getAsString().equals(cols.get(i).getAsString())) pkIdx = i;
            }
            if (pkIdx >= 0) {
                String pkVal = head.getAsJsonArray("rows").get(0).getAsJsonArray()
                        .get(pkIdx).getAsString();
                JsonObject bq = new JsonObject();
                bq.addProperty("table", spec.get("bodyTable").getAsString());
                if (spec.has("bodyCols")) bq.add("cols", spec.get("bodyCols"));
                else bq.add("cols", spec.get("cols"));
                bq.addProperty("where", spec.get("bodyFk").getAsString() + "='" + pkVal + "'");
                out.add("body", query(s, bq));
            }
        }
        return out;
    }

    // ---------- billSave：JSON -> 聚合 VO -> 平台 SAVE ----------

    /**
     * {"action":"billSave","billType":"D3","voClass":"nc.vo.ep.dj.DJZBVO"(默认可省),
     *  "corp":"公司pk(缺省: parent.pk_corp > profile.corp > 登录公司)",
     *  "pkMap":{"parent":"vouchid","child":"fb_oid","fk":"vouchid"}(arap 默认内置),
     *  "parent":{...},"children":[{...}],
     *  "defaults":{"parent":{...},"item":{...}},
     *  "saveAction":"SAVE"(默认), "dryRun":true(只建VO+checkWorkitemOnSave不落库)}
     * 实测行为（U8C arap SAVE）：不传 djbh 时服务端按编号规则自动取号；
     * 落库主键以 saveRet[0] 为准（可能覆盖客户端预生成 vouchid）；
     * status/dr 未传时自动置 NEW/0；verified 字段为回查结果。
     */
    public static JsonObject billSave(Session s, JsonObject spec) throws Exception {
        String billType = spec.has("billType") ? spec.get("billType").getAsString() : null;
        if (billType == null) throw new IllegalArgumentException("需要 billType");
        String voClass = spec.has("voClass") ? spec.get("voClass").getAsString() : ARAP_VO;
        String saveAction = spec.has("saveAction") ? spec.get("saveAction").getAsString() : "SAVE";
        boolean dryRun = spec.has("dryRun") && spec.get("dryRun").getAsBoolean();

        JsonObject parent = new JsonObject();
        JsonObject profHead = Profile.getObj("bill." + billType + ".headDefaults");
        if (profHead != null) mergeInto(parent, profHead);
        if (spec.has("defaults") && spec.get("defaults").isJsonObject()
                && spec.getAsJsonObject("defaults").has("parent"))
            mergeInto(parent, spec.getAsJsonObject("defaults").getAsJsonObject("parent"));
        if (spec.has("parent") && spec.get("parent").isJsonObject())
            mergeInto(parent, spec.getAsJsonObject("parent"));

        JsonArray children = new JsonArray();
        JsonObject itemDef = new JsonObject();
        JsonObject profItem = Profile.getObj("bill." + billType + ".itemDefaults");
        if (profItem != null) mergeInto(itemDef, profItem);
        if (spec.has("defaults") && spec.get("defaults").isJsonObject()
                && spec.getAsJsonObject("defaults").has("item"))
            mergeInto(itemDef, spec.getAsJsonObject("defaults").getAsJsonObject("item"));
        if (spec.has("children") && spec.get("children").isJsonArray()) {
            for (JsonElement e : spec.getAsJsonArray("children")) {
                JsonObject it = new JsonObject();
                mergeInto(it, itemDef);
                mergeInto(it, e.getAsJsonObject());
                children.add(it);
            }
        }

        // corp 解析：spec > parent.pk_corp > profile.corp > 登录公司
        String corp = spec.has("corp") && !spec.get("corp").isJsonNull()
                ? spec.get("corp").getAsString() : null;
        if (corp == null && parent.has("pk_corp") && !parent.get("pk_corp").isJsonNull())
            corp = parent.get("pk_corp").getAsString();
        if (corp == null) corp = Profile.get("corp");
        if (corp == null) corp = s.getPkCorp();
        if (corp == null) throw new IllegalStateException("无法确定 corp，请显式传 corp");
        if (!parent.has("pk_corp") || parent.get("pk_corp").isJsonNull())
            parent.addProperty("pk_corp", corp);

        // 主键/外键补齐：arap 内置 vouchid/djvbid；其他 VO 经 pkMap 指定，缺省跳过并提示
        JsonObject pkMap = spec.has("pkMap") && spec.get("pkMap").isJsonObject()
                ? spec.getAsJsonObject("pkMap") : defaultPkMap(voClass);
        JsonObject note = new JsonObject();
        if (pkMap != null) {
            String pPk = pkMap.has("parent") ? pkMap.get("parent").getAsString() : null;
            String cPk = pkMap.has("child") ? pkMap.get("child").getAsString() : null;
            String fk = pkMap.has("fk") ? pkMap.get("fk").getAsString() : pPk;
            if (pPk != null && (!parent.has(pPk) || parent.get(pPk).isJsonNull()))
                parent.addProperty(pPk, s.genOid(corp));
            String parentPkVal = pPk != null ? parent.get(pPk).getAsString() : null;
            for (JsonElement e : children) {
                JsonObject it = e.getAsJsonObject();
                if (cPk != null && (!it.has(cPk) || it.get(cPk).isJsonNull()))
                    it.addProperty(cPk, s.genOid(corp));
                if (fk != null && parentPkVal != null && (!it.has(fk) || it.get(fk).isJsonNull()))
                    it.addProperty(fk, parentPkVal);
            }
        } else {
            note.addProperty("pkMap", "未知 VO 类未做主键补齐，请用 pkMap 指定或在字段中自带");
        }
        putIfAbsent(parent, "dr", 0);
        putIfAbsent(parent, "status", VO_NEW);
        for (JsonElement e : children) {
            JsonObject it = e.getAsJsonObject();
            putIfAbsent(it, "dr", 0);
            putIfAbsent(it, "status", VO_NEW);
        }

        JsonObject voJson = new JsonObject();
        voJson.addProperty("_class", voClass);
        voJson.add("parent", parent);
        voJson.add("children", children);
        AggregatedValueObject vo = (AggregatedValueObject) JsonCodec.toAggVo(
                Class.forName(voClass), voJson);

        JsonObject out = new JsonObject();
        out.addProperty("billType", billType);
        out.addProperty("corp", corp);
        out.add("note", note);
        out.add("vo", JsonCodec.toJson(vo)); // 回显类型转换后的 VO，便于核对字段是否生效

        if (dryRun) {
            // 只跑 checkWorkitemOnSave：服务端校验缺字段会在此报错，不落库
            java.util.HashMap<String, Object> ep = new java.util.HashMap<String, Object>();
            Object wfVo = new Invoker(s).invokeRaw("nc.itf.uap.pf.IWorkflowMachine",
                    "checkWorkitemOnSave",
                    Arrays.asList("java.lang.String", "java.lang.String", "java.lang.String",
                            "nc.vo.pub.AggregatedValueObject", "java.util.HashMap"),
                    saveAction, billType, now(), vo, ep);
            out.addProperty("dryRun", true);
            out.addProperty("wfVoPresent", wfVo != null);
            return out;
        }

        Object ret = Flow.process(s, new Invoker(s), saveAction, billType, vo, out);
        out.add("saveRet", JsonCodec.toJson(ret));

        // arap 回查验证：服务端实际落库主键以 saveRet 返回值为准
        // （客户端预生成的 vouchid 可能被服务端重编号覆盖，实测如此）
        if (ARAP_VO.equals(voClass)) {
            String vid = extractSavedPk(ret);
            if (vid == null && parent.has("vouchid"))
                vid = parent.get("vouchid").getAsString();
            if (vid != null) {
                JsonArray chk = Docs.tableQuery(s, "arap_djzb",
                        new String[]{"vouchid", "djbh", "djlxbm", "djdl", "spzt", "djzt", "dr"},
                        "vouchid='" + vid + "'");
                out.add("bills", chk);
                out.addProperty("verified", chk.size() > 0);
            } else {
                out.addProperty("verified", false);
                note.addProperty("verify", "saveRet 中未识别出落库主键，请按 saveRet.djbh 自行回查");
            }
        }
        return out;
    }

    // ---------- billAction：对既有单据执行动作 ----------

    /**
     * {"action":"billAction","op":"QUERY|EDIT|APPROVE|AUDIT|UNAUDIT|DELETE|UPDATE|INSPECT|...",
     *  "vouchid":"..." | "djbh":"...", "billType":"可选(缺省按vouchid查djlxbm)",
     *  "fields":{...},"itemFields":{...}}
     * 除 QUERY 外统一委托 Docs.billAction（卡片审核/平台动作分支）。
     */
    public static JsonObject billAction(Session s, JsonObject spec) throws Exception {
        String vouchid = spec.has("vouchid") && !spec.get("vouchid").isJsonNull()
                ? spec.get("vouchid").getAsString() : null;
        if (vouchid == null) {
            String djbh = spec.has("djbh") ? spec.get("djbh").getAsString() : null;
            if (djbh == null) throw new IllegalArgumentException("需要 vouchid 或 djbh");
            JsonArray r = Docs.tableQuery(s, "arap_djzb", new String[]{"vouchid"},
                    "djbh='" + djbh.replace("'", "''") + "' and dr=0 order by ts desc");
            if (r.size() == 0) throw new IllegalStateException("单据不存在: " + djbh);
            vouchid = r.get(0).getAsJsonArray().get(0).getAsString();
        }
        if (!spec.has("billtype") && !spec.has("billType")) {
            JsonArray r = Docs.tableQuery(s, "arap_djzb", new String[]{"djlxbm"},
                    "vouchid='" + vouchid + "'");
            if (r.size() > 0 && !r.get(0).getAsJsonArray().get(0).isJsonNull())
                spec.addProperty("billtype", r.get(0).getAsJsonArray().get(0).getAsString());
        } else if (spec.has("billType")) {
            spec.addProperty("billtype", spec.get("billType").getAsString());
        }
        spec.addProperty("vouchid", vouchid);

        String op = spec.has("op") ? spec.get("op").getAsString() : null;
        if (op == null && spec.has("act")) op = spec.get("act").getAsString();
        if (op == null) throw new IllegalArgumentException("需要 op");
        if ("QUERY".equalsIgnoreCase(op)) {
            Object vo = new Invoker(s).invokeRaw("nc.itf.arap.pub.IArapBillPublic",
                    "findArapBillByPK", Arrays.asList("java.lang.String"), vouchid);
            JsonObject out = new JsonObject();
            out.addProperty("vouchid", vouchid);
            out.add("vo", JsonCodec.toJson(vo));
            JsonArray head = Docs.tableQuery(s, "arap_djzb",
                    new String[]{"djbh", "djlxbm", "djdl", "spzt", "djzt", "dr", "ts"},
                    "vouchid='" + vouchid + "'");
            out.add("head", head);
            return out;
        }
        spec.addProperty("act", op);
        return Docs.billAction(s, spec);
    }

    // ---------- 内部 ----------

    /** processAction 返回值结构：数组/List 首元素为落库头 pk（String），第二元素为回写 VO */
    private static String extractSavedPk(Object ret) {
        try {
            Object first = null;
            if (ret instanceof Object[]) {
                Object[] a = (Object[]) ret;
                if (a.length > 0) first = a[0];
            } else if (ret instanceof java.util.List) {
                java.util.List<?> l = (java.util.List<?>) ret;
                if (!l.isEmpty()) first = l.get(0);
            } else if (ret instanceof String) {
                first = ret;
            }
            if (first instanceof String && ((String) first).length() >= 10) return (String) first;
        } catch (Exception ignore) {
        }
        return null;
    }

    private static JsonObject defaultPkMap(String voClass) {
        if (ARAP_VO.equals(voClass)) {
            // DJZBVO：表头主键 vouchid；表体行主键 fb_oid（arap_djfb 行 pk），外键 vouchid 回指表头
            JsonObject m = new JsonObject();
            m.addProperty("parent", "vouchid");
            m.addProperty("child", "fb_oid");
            m.addProperty("fk", "vouchid");
            return m;
        }
        return null;
    }

    private static void mergeInto(JsonObject dst, JsonObject src) {
        for (Map.Entry<String, JsonElement> e : src.entrySet()) dst.add(e.getKey(), e.getValue());
    }

    private static void putIfAbsent(JsonObject o, String k, int v) {
        if (!o.has(k) || o.get(k).isJsonNull()) o.addProperty(k, v);
    }

    private static String[] toStrArr(JsonArray a) {
        String[] r = new String[a.size()];
        for (int i = 0; i < a.size(); i++) r[i] = a.get(i).getAsString();
        return r;
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }
}
