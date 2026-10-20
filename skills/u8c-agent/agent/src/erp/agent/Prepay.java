package erp.agent;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import nc.vo.pub.AggregatedValueObject;
import nc.vo.pub.CircularlyAccessibleValueObject;
import nc.vo.pub.lang.UFDouble;

/**
 * 复刻人工链路：采购订单 → 辅助功能→预付款 → 比例弹窗(PrePayDlg) →
 * 订单VO经 CHG21TOF3 客户端脚本转换成付款单VO(DJZBVO, djlxbm=D3) →
 * 「付款录入」卡(NEW 态)填收款银行 → 保存 = processAction("SAVE","D3")。
 *
 * 依据反编译：
 *  nc.ui.po.oper.PoToftPanel.onBillPrePay   —— 弹窗确认→折算→转换→开付款录入卡
 *  nc.ui.pf.changedir.CHG21TOF3            —— 21→F3 字段映射(D3 是 F3 交易类型)
 *  nc.ui.arap.actions.edit.SaveActionNor   —— 新单保存 processAction("SAVE", djlxbm)
 *  nc.ui.pub.pf.PfUtilClient.runAction     —— SAVE→checkOnSave→checkWorkitemOnSave→
 *                                            前台业务插件→IplatFormEntry.processAction
 */
public final class Prepay {

    private Prepay() {
    }

    /**
     * 对订单执行「预付款→付款录入→保存」。
     * amount <= 0 时按可预付全额（UI 默认 dPermit 全部）。
     */
    public static JsonObject entrySave(Session s, String orderPk, String corp, double amount)
            throws Exception {
        Invoker inv = new Invoker(s);
        JsonObject out = new JsonObject();

        // ---- 1. 订单VO（UI 在预付款入口重新加载 queryOrderVOByKey） ----
        AggregatedValueObject orderVo = (AggregatedValueObject) inv.invokeRaw(
                "nc.itf.po.IOrder", "queryOrderVOByKey",
                Arrays.asList("java.lang.String"), orderPk);
        CircularlyAccessibleValueObject ohead = orderVo.getParentVO();
        CircularlyAccessibleValueObject[] obody = orderVo.getChildrenVO();
        if (ohead == null || obody == null || obody.length == 0)
            throw new IllegalStateException("订单VO为空: " + orderPk);

        // ---- 2. 行有效性（UI: iisactive!=0 直接终止） ----
        List<CircularlyAccessibleValueObject> actItems =
                new ArrayList<CircularlyAccessibleValueObject>();
        Set<String> billBids = new HashSet<String>();
        for (CircularlyAccessibleValueObject it : obody) {
            Object act = it.getAttributeValue("iisactive");
            if (act != null && ((Number) act).intValue() != 0)
                throw new IllegalStateException("订单存在失效行，不能转预付款（同UI规则）");
            actItems.add(it);
            Object bid = it.getAttributeValue("corder_bid");
            if (bid != null) billBids.add(bid.toString());
        }

        // ---- 3. 可预付总额 = 非赠品行 noriginaltaxpricemny 之和 - 已有fk单据占用 ----
        UFDouble dTotal = new UFDouble(0);
        for (CircularlyAccessibleValueObject it : actItems) {
            if (isGift(it)) continue;
            Object p = it.getAttributeValue("noriginaltaxpricemny");
            if (p instanceof UFDouble) dTotal = dTotal.add((UFDouble) p);
        }
        if (dTotal.compareTo(new UFDouble(0)) == 0)
            throw new IllegalStateException("订单可预付金额为0");

        Object voD3s = inv.invokeRaw("nc.itf.po.assistquery.IPoAssisOper",
                "querybyOutId", Arrays.asList("java.lang.String"), orderPk);
        if (voD3s instanceof AggregatedValueObject[]) {
            for (AggregatedValueObject d3 : (AggregatedValueObject[]) voD3s) {
                CircularlyAccessibleValueObject[] its = d3.getChildrenVO();
                if (its == null) continue;
                for (CircularlyAccessibleValueObject di : its) {
                    Object ddhh = di.getAttributeValue("ddhh");
                    Object jf = di.getAttributeValue("jfybje");
                    if (ddhh != null && !billBids.contains(ddhh.toString())
                            && jf instanceof UFDouble)
                        dTotal = dTotal.sub((UFDouble) jf);
                }
            }
        }
        if (dTotal.compareTo(new UFDouble(0)) <= 0)
            throw new IllegalStateException("剩余可预付金额<=0（已被占满）");

        UFDouble dMny = amount > 0 ? new UFDouble(amount) : dTotal;
        if (dMny.compareTo(dTotal) > 0)
            throw new IllegalArgumentException("预付金额超出可预付余额: amount=" + amount
                    + " permit=" + dTotal);
        UFDouble dRate = dMny.div(dTotal);
        out.addProperty("prepayAmount", dMny.toString());
        out.addProperty("prepayRate", dRate.toString());

        // ---- 4. 按比例改写行金额（PrePayDlg 确认后的处理，PoToftPanel） ----
        int iDigit = 2; // 人民币两位精度；外币订单暂不支持
        UFDouble rowTotal = new UFDouble(0);
        int nonGift = 0;
        for (CircularlyAccessibleValueObject it : actItems)
            if (!isGift(it)) nonGift++;
        int seen = 0;
        for (CircularlyAccessibleValueObject it : actItems) {
            if (isGift(it)) continue;
            seen++;
            UFDouble price = (UFDouble) it.getAttributeValue("noriginaltaxpricemny");
            UFDouble tax = (UFDouble) it.getAttributeValue("noriginaltaxmny");
            UFDouble newPrice = (seen < nonGift)
                    ? price.multiply(dRate).setScale(iDigit, 4)
                    : dMny.sub(rowTotal).setScale(iDigit, 4);
            if (seen < nonGift)
                rowTotal = rowTotal.add(newPrice);
            it.setAttributeValue("noriginaltaxpricemny", newPrice);
            if (tax != null)
                it.setAttributeValue("noriginaltaxmny",
                        tax.multiply(dRate).setScale(iDigit, 4));
            UFDouble newTax = (UFDouble) it.getAttributeValue("noriginaltaxmny");
            it.setAttributeValue("noriginalcurmny",
                    newPrice.sub(newTax == null ? new UFDouble(0) : newTax)
                            .setScale(iDigit, 4));
            it.setAttributeValue("nordernum", null);
            it.setAttributeValue("norgnettaxprice", null);
            it.setAttributeValue("norgtaxprice", null);
            it.setAttributeValue("noriginalcurprice", null);
            it.setAttributeValue("noriginalnetprice", null);
        }

        // ---- 5. 单据转换映射：queryBillMap("21","fk",corp) → 目标类型+业务类型 ----
        Object mapVo = inv.invokeRaw("nc.itf.arap.pub.IArapBillMapQureyPublic",
                "queryBillMap",
                Arrays.asList("java.lang.String", "java.lang.String", "java.lang.String"),
                "21", "fk", corp);
        String destType = "D3";
        String busiType = null;
        if (mapVo != null) {
            Object t = beanGet(mapVo, "targetbilltype");
            Object b = beanGet(mapVo, "busitype");
            if (t != null) destType = t.toString();
            if (b != null) busiType = b.toString();
        }
        if (busiType != null) ohead.setAttributeValue("cbiztype", busiType);
        out.addProperty("destBillType", destType);
        out.addProperty("busiType", busiType);

        // ---- 6. 本币折算（PoChangeUI.setVONativeAndAssistCurrValue 等价，汇率=1） ----
        for (CircularlyAccessibleValueObject it : actItems) {
            Object rate = it.getAttributeValue("nexchangeotobrate");
            if (rate instanceof UFDouble
                    && ((UFDouble) rate).compareTo(new UFDouble(1)) != 0)
                throw new IllegalStateException("外币订单暂未支持（汇率!=1）");
            Object cur = it.getAttributeValue("noriginalcurmny");
            UFDouble taxPrice = (UFDouble) it.getAttributeValue("noriginaltaxpricemny");
            it.setAttributeValue("nmoney", cur);
            it.setAttributeValue("ntaxpricemny", taxPrice);
            if (taxPrice != null)
                it.setAttributeValue("ntaxmny", taxPrice.sub(
                        cur instanceof UFDouble ? (UFDouble) cur : new UFDouble(0)));
        }

        // ---- 7. CHG21TOF3 客户端脚本转换（order→付款单DJZBVO） ----
        AggregatedValueObject dest = convertOrderToDjzb(s, inv, orderVo, corp,
                destType, busiType);
        CircularlyAccessibleValueObject dhead = dest.getParentVO();
        CircularlyAccessibleValueObject[] ditems = dest.getChildrenVO();

        // ---- 8. 付款录入页填银行（表体放大镜字段 + 表头同步，DjPanel:603-628） ----
        fillBankFields(s, dhead, ditems, corp, orderPk, out);

        // ---- 9. 保存：checkWorkitemOnSave("SAVE") → 前台业务插件 → processAction ----
        Object ret = Flow.process(s, inv, "SAVE", destType, dest, out);
        out.add("saveRet", JsonCodec.toJson(ret));

        // ---- 9.5 联动审核推式 yf 草稿：手工链路的草稿是 spzt=1 已审态 ----
        JsonArray yf = Docs.tableQuery(s, "arap_djzb",
                new String[]{"vouchid", "djbh", "spzt"},
                "djdl='yf' and vouchid in (select vouchid from arap_djfb where ddhid='"
                        + orderPk + "')");
        JsonArray audited = new JsonArray();
        for (JsonElement e : yf) {
            JsonArray d = e.getAsJsonArray();
            String yid = d.get(0).getAsString();
            if (!d.get(2).isJsonNull() && "1".equals(d.get(2).getAsString())) {
                continue;
            }
            Object yvo = inv.invokeRaw("nc.itf.arap.pub.IArapBillPublic",
                    "findArapBillByPK", Arrays.asList("java.lang.String"), yid);
            yvo.getClass().getMethod("setm_OldVO", yvo.getClass()).invoke(yvo, yvo);
            Object amsg = inv.invokeRaw("nc.itf.arap.pub.IArapBillPublic",
                    "auditArapBill", Arrays.asList("nc.vo.ep.dj.DJZBVO"), yvo);
            audited.add(d.get(1).getAsString());
            out.add("auditRet", JsonCodec.toJson(amsg));
        }
        out.add("auditedDrafts", audited);

        // ---- 10. 回查：单据 + 审批状态 ----
        JsonArray rows = Docs.tableQuery(s, "arap_djzb",
                new String[]{"vouchid", "djbh", "spzt", "djzt", "ts", "djlxbm", "djdl"},
                "vouchid in (select vouchid from arap_djfb where ddhid='" + orderPk + "')");
        out.add("bills", rows);
        return out;
    }

    /** 运行真实 CHG21TO* 客户端转换脚本（缓存在 CODE/nc/ui/pf/changedir/） */
    private static AggregatedValueObject convertOrderToDjzb(Session s, Invoker inv,
            AggregatedValueObject orderVo, String corp, String destType, String busiType)
            throws Exception {
        // 转换前钩子：源单 VO 实现 ISourceChangeVO 时调 adjustBeforeChange
        Class<?> srcItf = Class.forName("nc.vo.pf.change.ISourceChangeVO");
        if (srcItf.isInstance(orderVo))
            srcItf.getMethod("adjustBeforeChange", String.class).invoke(orderVo, destType);

        // 交易类型找不到转换类时按真实单据类型回退（PfUtilUITools.findUIChangeScriptClass）
        Class<?> convCls = loadChangeClass("21", destType);
        Object conv = convCls.getDeclaredConstructor().newInstance();
        call(conv, "setSourceBilltype", new Class<?>[]{String.class}, "21");
        call(conv, "setDestBilltype", new Class<?>[]{String.class}, destType);
        Object destBusi = inv.invokeRaw("nc.itf.uap.pf.IPFConfig", "findDestBusitype",
                Arrays.asList("java.lang.String", "java.lang.String"),
                busiType != null ? busiType : "", destType);
        call(conv, "setDestBusitype", new Class<?>[]{String.class},
                destBusi == null ? null : destBusi.toString());
        String day = today();
        call(conv, "setSysDate", new Class<?>[]{String.class}, day);
        call(conv, "setSysOperator", new Class<?>[]{String.class}, s.getUserId());
        call(conv, "setSysCorp", new Class<?>[]{String.class}, corp);
        call(conv, "setSysAccountYear", new Class<?>[]{String.class}, day.substring(0, 4));
        call(conv, "setSysTime", new Class<?>[]{String.class}, now());

        // pfInitVoClass(destType, n)：构造空聚合VO（头+体）
        CircularlyAccessibleValueObject[] srcItems = orderVo.getChildrenVO();
        Class<?> djzbCls = Class.forName("nc.vo.ep.dj.DJZBVO");
        Class<?> djhCls = Class.forName("nc.vo.ep.dj.DJZBHeaderVO");
        Class<?> djiCls = Class.forName("nc.vo.ep.dj.DJZBItemVO");
        AggregatedValueObject dest = (AggregatedValueObject) djzbCls.getDeclaredConstructor()
                .newInstance();
        dest.setParentVO(
                (CircularlyAccessibleValueObject) djhCls.getDeclaredConstructor().newInstance());
        CircularlyAccessibleValueObject[] items = (CircularlyAccessibleValueObject[]) Array
                .newInstance(djiCls, srcItems.length);
        for (int i = 0; i < items.length; i++)
            items[i] = (CircularlyAccessibleValueObject) djiCls.getDeclaredConstructor()
                    .newInstance();
        dest.setChildrenVO(items);

        AggregatedValueObject result = (AggregatedValueObject) convCls
                .getMethod("retChangeBusiVO", AggregatedValueObject.class,
                        AggregatedValueObject.class)
                .invoke(conv, orderVo, dest);

        // 转换后钩子
        if (srcItf.isInstance(orderVo))
            srcItf.getMethod("adjustAfterChange", String.class).invoke(orderVo, destType);
        Class<?> tgtItf = Class.forName("nc.vo.pf.change.ITargetChangeVO");
        if (result != null && tgtItf.isInstance(result))
            tgtItf.getMethod("adjustAfterChange", String.class).invoke(result, "21");

        return result;
    }

    /** CHG{src}TO{dest}，交易类型回退父类型（21→D3 实为 CHG21TOF3） */
    private static Class<?> loadChangeClass(String srcType, String destType)
            throws ClassNotFoundException {
        try {
            return Class.forName("nc.ui.pf.changedir.CHG" + srcType + "TO" + destType);
        } catch (ClassNotFoundException e) {
            String real = realBilltype(destType);
            if (!real.equals(destType))
                return Class.forName(
                        "nc.ui.pf.changedir.CHG" + srcType + "TO" + real);
            throw e;
        }
    }

    private static String realBilltype(String code) {
        try {
            Class<?> tools = Class.forName("nc.vo.pf.change.PfUtilBaseTools");
            Object r = tools.getMethod("getRealBilltype", String.class).invoke(null, code);
            return r == null ? code : r.toString();
        } catch (Exception e) {
            // 无头环境缓存不可用时按已知映射回退：D3(采购付款单)→F3
            return "D3".equals(code) ? "F3" : code;
        }
    }

    /**
     * 付款录入页银行字段：放大镜选的是表体行 bd_custbank 参照，
     * 保存时表体值复制到表头并把账号解析为 bd_bankaccbas.pk（DjPanel）。
     */
    private static void fillBankFields(Session s, CircularlyAccessibleValueObject dhead,
            CircularlyAccessibleValueObject[] ditems, String corp, String orderPk,
            JsonObject out) throws Exception {
        // 公司付款账户：profile payDoc.payerAccountCode 指定（bd_bankaccbas.accountcode）
        String payerAccCode = Profile.require("payDoc.payerAccountCode",
                "值为 bd_bankaccbas.accountcode，可用 query 动作查询本环境付款账户");
        JsonArray bankRows = Docs.tableQuery(s, "bd_bankaccbas",
                new String[]{"pk_bankaccbas", "accountname"},
                "pk_corp='" + corp + "' and accountcode='"
                        + payerAccCode.replace("'", "''") + "'");
        if (bankRows.size() == 0)
            throw new IllegalStateException("公司付款账户(accountcode=" + payerAccCode + ")未找到");
        JsonArray br = bankRows.get(0).getAsJsonArray();
        String compAccPk = br.get(0).isJsonNull() ? null : br.get(0).getAsString();
        String compAccName = br.get(1).isJsonNull() ? null : br.get(1).getAsString();

        // 供应商收款账户：cumandoc → cubasdoc → custbank → bankaccbas
        // 供应商主键来源：优先转换VO头的客户/供应商字段，否则回查订单 cvendormangid
        Object vendPk = dhead.getAttributeValue("cvendorbaseid");
        if (vendPk == null) vendPk = dhead.getAttributeValue("hbbm");
        if (vendPk == null) vendPk = dhead.getAttributeValue("pk_supplier");
        if (vendPk == null) {
            JsonArray v = Docs.tableQuery(s, "po_order",
                    new String[]{"cvendormangid"}, "corderid='" + orderPk + "'");
            if (v.size() > 0 && !v.get(0).getAsJsonArray().get(0).isJsonNull())
                vendPk = v.get(0).getAsJsonArray().get(0).getAsString();
        }
        String custbankPk = null, accbankPk = null, account = null, accName = null;
        if (vendPk != null) {
            JsonArray sup = Docs.tableQuery(s, "bd_cumandoc",
                    new String[]{"pk_cubasdoc"},
                    "pk_cumandoc='" + vendPk + "'");
            if (sup.size() > 0 && !sup.get(0).getAsJsonArray().get(0).isJsonNull()) {
                String cubPk = sup.get(0).getAsJsonArray().get(0).getAsString();
                JsonArray cb = Docs.tableQuery(s, "bd_custbank",
                        new String[]{"pk_custbank", "pk_accbank", "accname", "account",
                                "defflag"},
                        "pk_cubasdoc='" + cubPk + "'");
                JsonArray picked = null;
                for (JsonElement e : cb) {
                    JsonArray r = e.getAsJsonArray();
                    if (r.get(0).isJsonNull()) continue;
                    if (picked == null || "Y".equals(
                            r.get(4).isJsonNull() ? "" : r.get(4).getAsString()))
                        picked = r;
                }
                if (picked != null) {
                    custbankPk = picked.get(0).getAsString();
                    accbankPk = picked.get(1).isJsonNull() ? null : picked.get(1).getAsString();
                    accName = picked.get(2).isJsonNull() ? null : picked.get(2).getAsString();
                    account = picked.get(3).isJsonNull() ? null : picked.get(3).getAsString();
                }
            }
        }
        if (custbankPk == null)
            throw new IllegalStateException("供应商收款银行账号未配置（放大镜必选，否则保存失败）");
        out.addProperty("vendorCustbankPk", custbankPk);

        // 供应商收款方开户行名称（联动公式 fkyhmc->bd_bankdoc.bankdocname by pk_bankdoc）
        String vendorBankName = queryBankdocName(s, accbankPk);
        String vendorBankdocPk = queryBankdocPk(s, accbankPk);
        // 我方开户行名称（bfyhzh.pk_bankdoc -> bankdocname，手工单 skyhmc=招商银行）
        String compBankName = queryBankdocName(s, compAccPk);
        // 对方收款账户的 pk_bankaccbas：custbank.pk_accbank 优先，否则按账号解析
        String skyPk = accbankPk != null ? accbankPk
                : resolveBankaccbas(s, account != null ? account : accName);

        // 卡片模板默认值（手工单落库值）：结算方式 pj_jsfs=我方账户对应 bd_balatype，
        // djxtflag/payflag=0（未付），zjldw=计量单位"次"
        String balatypePk = null;
        if (compAccName != null) {
            // balatype 挂在集团层（pk_corp=0001），不限定公司过滤
            JsonArray bt = Docs.tableQuery(s, "bd_balatype",
                    new String[]{"pk_balatype"},
                    "balanname='" + compAccName.replace("'", "''") + "'");
            if (bt.size() > 0 && !bt.get(0).getAsJsonArray().get(0).isJsonNull())
                balatypePk = bt.get(0).getAsJsonArray().get(0).getAsString();
        }
        String zjldwPk = null;
        String zjldwName = Profile.get("payDoc.zjldwMeasName");
        if (zjldwName != null) {
            JsonArray md = Docs.tableQuery(s, "bd_measdoc",
                    new String[]{"pk_measdoc"},
                    "measname='" + zjldwName.replace("'", "''") + "'");
            if (md.size() > 0 && !md.get(0).getAsJsonArray().get(0).isJsonNull())
                zjldwPk = md.get(0).getAsJsonArray().get(0).getAsString();
        }

        // 表体行：放大镜选定后写 dfyhzh(供应商pk_bankaccbas)/bankacc_fk(账号)/pk_bankdoc，
        // 公式带出 fkyhmc=对方开户行名；bfyhzh=我方付款账户，skyhmc=我方开户行名（手工单为准）
        for (CircularlyAccessibleValueObject it : ditems) {
            it.setAttributeValue("bfyhzh", compAccPk);
            it.setAttributeValue("dfyhzh", skyPk);
            if (account != null) {
                it.setAttributeValue("skyhzh", account);
                it.setAttributeValue("bankacc_fk", account);
            }
            if (vendorBankdocPk != null) it.setAttributeValue("pk_bankdoc", vendorBankdocPk);
            if (vendorBankName != null) it.setAttributeValue("fkyhmc", vendorBankName);
            if (compBankName != null) it.setAttributeValue("skyhmc", compBankName);
            if (balatypePk != null) it.setAttributeValue("pj_jsfs", balatypePk);
            if (zjldwPk != null) it.setAttributeValue("zjldw", zjldwPk);
            if (it.getAttributeValue("djxtflag") == null)
                it.setAttributeValue("djxtflag", Integer.valueOf(0));
            if (it.getAttributeValue("payflag") == null)
                it.setAttributeValue("payflag", Integer.valueOf(0));
        }

        // 表头：UI 保存时表体值复制到表头（transient 字段，不落库但参与校验/公式）；
        // pj_jsfs 由服务端 setBodyDefault 从头复制到体
        dhead.setAttributeValue("skyhzh", skyPk);
        dhead.setAttributeValue("bfyhzh", compAccPk);
        dhead.setAttributeValue("dfyhzh", skyPk);
        if (compBankName != null) dhead.setAttributeValue("skyhmc", compBankName);
        if (vendorBankName != null) dhead.setAttributeValue("fkyhmc", vendorBankName);
        if (balatypePk != null) dhead.setAttributeValue("pj_jsfs", balatypePk);
        // 卡片默认项（付款类型等自定义字段）：profile payDoc.headDefaults 逐字段补 null 值
        JsonObject headDef = Profile.getObj("payDoc.headDefaults");
        if (headDef != null) {
            for (java.util.Map.Entry<String, JsonElement> e : headDef.entrySet()) {
                if (dhead.getAttributeValue(e.getKey()) == null
                        && e.getValue().isJsonPrimitive())
                    dhead.setAttributeValue(e.getKey(), e.getValue().getAsString());
            }
        }

        JsonObject dbg = new JsonObject();
        dbg.addProperty("h_fkyhzh", str(dhead.getAttributeValue("fkyhzh")));
        dbg.addProperty("h_skyhzh", str(dhead.getAttributeValue("skyhzh")));
        dbg.addProperty("h_accountid", str(dhead.getAttributeValue("accountid")));
        dbg.addProperty("b0_fkyhzh", ditems.length > 0 ? str(ditems[0].getAttributeValue("fkyhzh")) : null);
        dbg.addProperty("b0_skyhzh", ditems.length > 0 ? str(ditems[0].getAttributeValue("skyhzh")) : null);
        dbg.addProperty("b0_bankacc", ditems.length > 0 ? str(ditems[0].getAttributeValue("bankacc")) : null);
        dbg.addProperty("h_dfyhzh", str(dhead.getAttributeValue("dfyhzh")));
        dbg.addProperty("h_bfyhzh", str(dhead.getAttributeValue("bfyhzh")));
        dbg.addProperty("b0_dfyhzh", ditems.length > 0 ? str(ditems[0].getAttributeValue("dfyhzh")) : null);
        dbg.addProperty("b0_bfyhzh", ditems.length > 0 ? str(ditems[0].getAttributeValue("bfyhzh")) : null);
        dbg.addProperty("h_djlxbm", str(dhead.getAttributeValue("djlxbm")));
        dbg.addProperty("h_djdl", str(dhead.getAttributeValue("djdl")));
        dbg.addProperty("balatypePk", balatypePk);
        dbg.addProperty("h_pj_jsfs", str(dhead.getAttributeValue("pj_jsfs")));
        dbg.addProperty("b0_pj_jsfs", ditems.length > 0
                ? str(ditems[0].getAttributeValue("pj_jsfs")) : null);
        out.add("bankDbg", dbg);
        System.err.println("[prepay-bank] " + dbg.toString());
    }

    private static String str(Object o) {
        return Flow.str(o);
    }

    /** 供应商开户行名：bankaccbas.pk_bankdoc → bd_bankdoc.bankdocname（联动公式同义） */
    private static String queryBankdocName(Session s, String accbankPk) throws Exception {
        String pk = queryBankdocPk(s, accbankPk);
        if (pk == null) return null;
        JsonArray b = Docs.tableQuery(s, "bd_bankdoc",
                new String[]{"bankdocname"}, "pk_bankdoc='" + pk + "'");
        return b.size() > 0 && !b.get(0).getAsJsonArray().get(0).isJsonNull()
                ? b.get(0).getAsJsonArray().get(0).getAsString() : null;
    }

    private static String queryBankdocPk(Session s, String accbankPk) throws Exception {
        if (accbankPk == null) return null;
        JsonArray r = Docs.tableQuery(s, "bd_bankaccbas",
                new String[]{"pk_bankdoc"}, "pk_bankaccbas='" + accbankPk + "'");
        return r.size() > 0 && !r.get(0).getAsJsonArray().get(0).isJsonNull()
                ? r.get(0).getAsJsonArray().get(0).getAsString() : null;
    }

    /** DjPanel 里的解析SQL：pk_bankaccbas by accountcode/pk */
    private static String resolveBankaccbas(Session s, String codeOrPk) throws Exception {
        if (codeOrPk == null) return null;
        String esc = codeOrPk.replace("'", "''");
        JsonArray r = Docs.tableQuery(s, "bd_bankaccbas",
                new String[]{"pk_bankaccbas"},
                "accountcode='" + esc + "' or pk_bankaccbas='" + esc + "'");
        return r.size() > 0 && !r.get(0).getAsJsonArray().get(0).isJsonNull()
                ? r.get(0).getAsJsonArray().get(0).getAsString() : null;
    }

    // ---------- 小工具（跨类共享的走 Flow） ----------
    private static void call(Object target, String name, Class<?>[] types, Object... args)
            throws Exception {
        Flow.call(target, name, types, args);
    }

    private static Object beanGet(Object o, String name) {
        return Flow.beanGet(o, name);
    }

    private static boolean isGift(CircularlyAccessibleValueObject it) {
        Object g = it.getAttributeValue("blargess");
        return g != null && Flow.truthy(g);
    }

    private static String today() {
        return new SimpleDateFormat("yyyy-MM-dd").format(new Date());
    }

    private static String now() {
        return Flow.now();
    }
}
