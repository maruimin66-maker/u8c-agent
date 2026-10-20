package erp.agent;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.Properties;

import nc.bs.framework.common.NCLocator;
import nc.itf.uap.sf.ISMVerifyService;
import nc.vo.sm.config.Account;
import nc.vo.sm.login.LoginSessBean;

/**
 * ERP 会话：装配 NCLocator、用个人账号执行真实登录流程，
 * 成功后把 clientToken 写入系统属性，后续业务调用自动携带。
 */
public final class Session {

    private final NCLocator locator;
    private LoginSessBean sessBean;

    public Session(String serverBase) {
        Properties props = new Properties();
        props.setProperty("SERVICEDISPATCH_URL", serverBase + "/ServiceDispatcherServlet");
        props.setProperty("SERVICELOOKUP_URL", serverBase + "/ServiceLookuperServlet");
        props.setProperty("CLIENT_COMMUNICATOR", "nc.bs.framework.comn.cli.JavaURLCommunicator");
        // 无参 NCLocator.getInstance() 依赖 InvocationInfoProxy 回退，供 BDOidGenerator 等内部类使用
        nc.bs.framework.common.InvocationInfoProxy proxy = nc.bs.framework.common.InvocationInfoProxy.getInstance();
        for (String k : props.stringPropertyNames()) {
            proxy.setProperty(k, props.getProperty(k));
        }
        this.locator = NCLocator.getInstance(props);
    }

    public Object lookup(String itfName) throws Exception {
        return locator.lookup(itfName);
    }

    /** 读服务端账套配置（登录页同源，匿名可调） */
    public Account[] listAccounts(String userCode, String password) throws Exception {
        Object cfg = locator.lookup("nc.itf.uap.sf.IConfigFileService");
        Object para = cfg.getClass().getMethod("getAccountConfigPara").invoke(cfg);
        Account[] accs = (Account[]) para.getClass().getMethod("getAryAccounts").invoke(para);
        return accs != null ? accs : new Account[0];
    }

    /**
     * 执行登录。返回 int 结果码（0 成功；6 已在线->自动强登重试）。
     * 成功后 clientToken/UserPKCorp/UserCode 已写入系统属性。
     */
    public int login(String userCode, String password, String accountCode,
                     String corpCode, String workDate) throws Exception {
        Account[] accs = listAccounts(userCode, password);
        if (accs == null || accs.length == 0) {
            return -100; // 无可用账套（通常密码错误或账号无权）
        }
        Account acc = pickAccount(accs, accountCode);
        if (acc == null) {
            return -101; // 指定账套不存在
        }

        ISMVerifyService sm = (ISMVerifyService) locator.lookup("nc.itf.uap.sf.ISMVerifyService");

        LoginSessBean lsb = tryYhtLogin(userCode, password, acc, corpCode, workDate, sm);
        if (lsb == null) {
            lsb = new LoginSessBean();
            lsb.setUserCode(userCode);
            lsb.setPassword(password);
            attachYhtId(lsb);
        }
        lsb.setAccountId(acc.getAccountCode());
        lsb.setDataSourceName(acc.getDataSourceName());
        lsb.setPk_corp(corpCode);
        lsb.setCorpCode(corpCode);
        lsb.setLanguage("simpchn");
        lsb.setWorkDate(workDate != null ? workDate : new SimpleDateFormat("yyyy-MM-dd").format(new Date()));
        lsb.setUserIp(localIp());
        String mac = localMac();
        if (mac != null) lsb.setUserMac(mac);
        lsb.setUserType("USER");
        lsb.setUserScreenWidth(1920);
        lsb.setUserScreenHeight(1080);
        lsb.setIsUserResolutionValid(true);

        Object[] objs = sm.login(lsb);
        int code = toCode(objs);
        if (code == 6) { // 已在线 -> 强制登录
            lsb.setForcedLogin(true);
            objs = sm.login(lsb);
            code = toCode(objs);
        }
        if (code != 0) {
            return code;
        }
        this.sessBean = (LoginSessBean) objs[1];
        // 客户端缓存框架（FiPubDataCache 等）靠 ClientEnvironment 取数据源名；UI 登录时自动注入，无头需手动补
        try {
            Object ce = Class.forName("nc.ui.pub.ClientEnvironment").getMethod("getInstance").invoke(null);
            ce.getClass().getMethod("setConfigAccount", Account.class).invoke(ce, acc);
        } catch (Throwable ignored) {
        }
        System.setProperty("clientToken", this.sessBean.getLoginToken());
        System.setProperty("UserPKCorp", lsb.getPk_crop());
        System.setProperty("UserCode", this.sessBean.getUserId());
        System.setProperty("UserDataSource", lsb.getDataSourceName());
        System.setProperty("userlogintime", lsb.getWorkDate());
        System.setProperty("USERID", this.sessBean.getUserId());
        System.setProperty("loginUserType", "0");
        return 0;
    }

    public LoginSessBean getSession() {
        return sessBean;
    }

    /** 当前登录用户 PK（制单人/操作员字段用） */
    public String getUserId() {
        return sessBean == null ? null : sessBean.getUserId();
    }

    /** 登录公司 PK（集团侧），单据 pk_corp 与其不同时会作为 groupPkCorp 传递 */
    public String getPkCorp() {
        return sessBean == null ? null : sessBean.getPk_crop();
    }

    public String getCorpName() {
        return sessBean == null ? null : sessBean.getCorpName();
    }

    /**
     * 友户通(YHT)登录路径（镜像 LoginUISupportIE.getLoginInfoYHT）：
     * 先把凭据注册到服务端，再用 userCode 调 verfiyYHT 换服务端构造的 lsb。
     * 返回 null 表示不是 YHT 用户或校验未过，调用方回落 NC 密码登录。
     */
    private LoginSessBean tryYhtLogin(String userCode, String password, Account acc,
                                      String corpCode, String workDate, ISMVerifyService sm) {
        try {
            sm.registLoginSessionBean(userCode, password);
            Object login2 = locator.lookup("nc.itf.uap.sf.ILogin2Service");
            Object bean = login2.getClass().getMethod("verfiyYHT", String.class).invoke(login2, userCode);
            return (LoginSessBean) bean;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 友户通(YHT)用户：用 userCode 换 yhtid 塞进会话（镜像官方 LoginUIControl.getYhtId）。
     * 失败静默跳过——普通账号没有 yhtid 也能登。
     */
    @SuppressWarnings("unchecked")
    private static void attachYhtId(LoginSessBean lsb) {
        try {
            String json = com.yonyou.yht.sdk.UserCenter.getUserByCode(lsb.getUserCode());
            if (json == null || json.isEmpty()) return;
            com.google.gson.JsonObject jo = new com.google.gson.JsonParser().parse(json).getAsJsonObject();
            String yhtid = null;
            for (String k : new String[]{"yhtid", "id", "userId"}) {
                if (jo.has(k) && !jo.get(k).isJsonNull()) { yhtid = jo.get(k).getAsString(); break; }
            }
            if (yhtid != null) lsb.put("yhtid", yhtid);
        } catch (Throwable ignored) {
        }
    }

    /** 生成单据主键 OID（NC 约定：客户端预生成 pk） */
    public String genOid(String pkCorp) throws Exception {
        Object gen = Class.forName("nc.itf.uap.bd.util.BDOidGenerator").newInstance();
        Object oid = gen.getClass().getMethod("nextOid", String.class).invoke(gen, pkCorp);
        return String.valueOf(oid);
    }

    private static int toCode(Object[] objs) {
        return objs != null && objs.length > 0 && objs[0] instanceof Integer
                ? ((Integer) objs[0]).intValue() : -102;
    }

    private static Account pickAccount(Account[] accs, String wantCode) {
        if (wantCode != null && !wantCode.isEmpty()) {
            for (Account a : accs) {
                if (wantCode.equals(a.getAccountCode())) return a;
            }
            return null;
        }
        for (Account a : accs) {
            if (a.canDoBusi() && !"0000".equals(a.getAccountCode())) return a;
        }
        for (Account a : accs) {
            if (a.canDoBusi()) return a;
        }
        return accs[0];
    }

    private static String localIp() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    private static String localMac() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                byte[] mac = ni.getHardwareAddress();
                if (mac == null) continue;
                StringBuilder sb = new StringBuilder();
                for (byte b : mac) sb.append(String.format("%02X", b));
                return sb.toString();
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
