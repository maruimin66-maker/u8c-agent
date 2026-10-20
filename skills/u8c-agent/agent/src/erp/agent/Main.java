package erp.agent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import nc.vo.sm.config.Account;
import nc.vo.sm.login.LoginSessBean;

/**
 * U8C/NC 无头驱动入口：stdin 读一行 JSON 请求，stdout 输出一行 JSON 结果。
 *
 * 环境变量：
 *   ERP_AGENT_SERVER    服务端基址（必填，如 http://<u8c-host>:<port>）
 *   ERP_AGENT_USER      登录账号（个人账号）
 *   ERP_AGENT_PASSWORD  登录密码（必填，仅存在于环境变量）
 *   ERP_AGENT_ACCOUNT   账套编码（可选，缺省自动选第一个可用账套）
 *   ERP_AGENT_CORP      登录公司编码（可选，默认 0001 集团）
 *   ERP_AGENT_WORKDATE  业务日期 yyyy-MM-dd（可选，默认当天）
 *   ERP_AGENT_PROFILE   本地档案 profile.json 路径（可选，写入链路缺省值）
 *   ERP_AGENT_CODE_DIR  UClient NCCACHE CODE 目录（可选，缺省自动发现）
 *   ERP_AGENT_JAVA      JRE8 java 路径（可选，缺省取 UClient 内置 JRE）
 *
 * 请求（详见 agent/README.md）：
 *   {"action":"accounts"}   {"action":"login"}   {"action":"describe","service":"..."}
 *   {"action":"invoke",...} {"action":"query",...} {"action":"billTypes"}
 *   {"action":"billSpec",...} {"action":"billSave",...} {"action":"billAction",...}
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] argv) {
        PrintStream out = System.out;
        System.setOut(new PrintStream(System.err)); // 框架日志全部重定向到 stderr
        JsonObject resp = new JsonObject();
        try {
            String line = readStdin();
            JsonObject req = new JsonParser().parse(line).getAsJsonObject();
            String server = env("ERP_AGENT_SERVER", null);
            if (server == null) {
                throw new IllegalStateException(
                        "缺少 ERP_AGENT_SERVER 环境变量（U8C 服务端基址，如 http://<host>:<port>）");
            }
            Session session = new Session(server);
            String user = env("ERP_AGENT_USER", null);
            String password = env("ERP_AGENT_PASSWORD", null);
            String action = req.get("action").getAsString();

            if ("describe".equals(action)) {
                resp.add("data", describeOnly(session, req));
            } else if ("invokeAnon".equals(action)) {
                resp.add("data", doInvoke(session, req));
            } else {
                if (user == null || password == null) {
                    throw new IllegalStateException("missing ERP_AGENT_USER/ERP_AGENT_PASSWORD");
                }
                login(session, user, password);
                if ("login".equals(action)) {
                    resp.add("data", sessionInfo(session));
                } else if ("genoid".equals(action)) {
                    int n = req.has("count") ? req.get("count").getAsInt() : 1;
                    com.google.gson.JsonArray oids = new com.google.gson.JsonArray();
                    String corp = req.has("pkCorp") ? req.get("pkCorp").getAsString() : "0001";
                    for (int i = 0; i < n; i++) {
                        oids.add(new com.google.gson.JsonPrimitive(session.genOid(corp)));
                    }
                    resp.add("data", oids);
                } else if ("accounts".equals(action)) {
                    resp.add("data", accountsInfo(session, user, password));
                } else if ("invoke".equals(action)) {
                    resp.add("data", doInvoke(session, req));
                } else if ("createPurchaseOrder".equals(action)) {
                    resp.add("data", Docs.createPurchaseOrder(session, req));
                } else if ("createPurchaseContract".equals(action)) {
                    resp.add("data", Docs.createPurchaseContract(session, req));
                } else if ("orderFromContract".equals(action)) {
                    resp.add("data", Docs.orderFromContract(session, req));
                } else if ("discardPurchaseOrder".equals(action)) {
                    resp.add("data", Docs.discardPurchaseOrder(session, req));
                } else if ("payAction".equals(action)) {
                    resp.add("data", Docs.billAction(session, req));
                } else if ("query".equals(action)) {
                    resp.add("data", Bill.query(session, req));
                } else if ("billTypes".equals(action)) {
                    resp.add("data", Bill.billTypes(session, req));
                } else if ("billSpec".equals(action)) {
                    resp.add("data", Bill.billSpec(session, req));
                } else if ("billSave".equals(action)) {
                    resp.add("data", Bill.billSave(session, req));
                } else if ("billAction".equals(action)) {
                    resp.add("data", Bill.billAction(session, req));
                } else if ("payEntry".equals(action)) {
                    String orderPk = req.get("orderPk").getAsString();
                    String corp = req.has("corp") ? req.get("corp").getAsString()
                            : Profile.get("corp");
                    if (corp == null) corp = session.getPkCorp();
                    if (corp == null) throw new IllegalStateException(
                            "payEntry 需要 corp（公司 pk），请显式传 corp 或在 profile 配置");
                    double amt = req.has("amount") ? req.get("amount").getAsDouble() : 0;
                    resp.add("data", Prepay.entrySave(session, orderPk, corp, amt));
                } else {
                    throw new IllegalArgumentException("unknown action: " + action);
                }
            }
            resp.addProperty("ok", true);
        } catch (Throwable t) {
            t.printStackTrace();
            resp.addProperty("ok", false);
            resp.addProperty("error", sanitize(t));
            resp.addProperty("errorType", t.getClass().getName());
        }
        out.println(resp.toString());
    }

    private static JsonElement describeOnly(Session s, JsonObject req) throws Exception {
        Invoker inv = new Invoker(s);
        List<String> ms = inv.describe(req.get("service").getAsString());
        JsonArray arr = new JsonArray();
        for (String m : ms) arr.add(new com.google.gson.JsonPrimitive(m));
        return arr;
    }

    private static void login(Session s, String user, String password) throws Exception {
        String account = env("ERP_AGENT_ACCOUNT", null);
        String corp = env("ERP_AGENT_CORP", "0001");
        String workDate = env("ERP_AGENT_WORKDATE", null);
        int code = s.login(user, password, account, corp, workDate);
        if (code != 0) {
            String msg = code >= 0 && code < nc.vo.sm.login.ILoginResult.RESULTSTRING.length
                    ? nc.vo.sm.login.ILoginResult.RESULTSTRING[code]
                    : ("login failed code=" + code);
            throw new IllegalStateException(msg);
        }
    }

    private static JsonElement sessionInfo(Session s) {
        LoginSessBean b = s.getSession();
        JsonObject jo = new JsonObject();
        jo.addProperty("userId", b.getUserId());
        jo.addProperty("userCode", b.getUserCode());
        jo.addProperty("userName", b.getUserName());
        jo.addProperty("accountId", b.getAccountId());
        jo.addProperty("corpPk", b.getPk_crop());
        jo.addProperty("corpName", b.getCorpName());
        String tok = b.getLoginToken();
        jo.addProperty("tokenLen", tok == null ? -1 : tok.length());
        jo.addProperty("propTokenLen", System.getProperty("clientToken") == null ? -1 : System.getProperty("clientToken").length());
        return jo;
    }

    private static JsonElement accountsInfo(Session s, String user, String password) throws Exception {
        Account[] accs = s.listAccounts(user, password);
        JsonArray arr = new JsonArray();
        if (accs != null) {
            for (Account a : accs) {
                JsonObject jo = new JsonObject();
                jo.addProperty("accountCode", a.getAccountCode());
                jo.addProperty("accountName", a.getAccountName());
                jo.addProperty("dataSource", a.getDataSourceName());
                arr.add(jo);
            }
        }
        return arr;
    }

    private static JsonElement doInvoke(Session s, JsonObject req) throws Exception {
        Invoker inv = new Invoker(s);
        List<String> argTypes = new ArrayList<String>();
        if (req.has("argTypes")) {
            for (JsonElement e : req.getAsJsonArray("argTypes")) argTypes.add(e.getAsString());
        }
        JsonArray args = req.has("args") ? req.getAsJsonArray("args") : new JsonArray();
        Object result = inv.invoke(
                req.get("service").getAsString(),
                req.get("method").getAsString(),
                argTypes, args);
        return JsonCodec.toJson(result);
    }

    private static String readStdin() throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(System.in, Charset.forName("UTF-8")));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        return sb.toString();
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isEmpty()) ? def : v;
    }

    private static String sanitize(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) cur = cur.getCause();
        String msg = cur.getMessage();
        String pwd = System.getenv("ERP_AGENT_PASSWORD");
        if (pwd != null && msg != null) msg = msg.replace(pwd, "***");
        return cur.getClass().getSimpleName() + ": " + (msg == null ? "" : msg);
    }
}
