package erp.agent;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 安装方本地档案配置：ERP_AGENT_PROFILE 指向一个 JSON 文件，
 * 存放本公司档案/字段映射（付款账户、组织、自定义项默认值等）。
 * 未配置时返回空对象——探测类动作不需要 profile，只有写入链路缺省值才依赖它。
 *
 * 约定结构（全部可选）：
 * {
 *   "corp": "1002",
 *   "payDoc":  {"payerAccountCode": "...", "zjldwMeasName": "...", "headDefaults": {...}},
 *   "order":   {"purchaseOrgPk": "...", "arriveStoreOrg": {"1002": "..."},
 *               "bizTypeNameField": "vdef8", "propagateHeadFields": ["vdef4"]},
 *   "contract": {"bizTypeField": "def8", "sourceBillType": "Z2"},
 *   "bill":    {"<djlxbm>": {"headDefaults": {...}, "itemDefaults": {...}}}
 * }
 */
public final class Profile {

    private static JsonObject cached;
    private static boolean loaded;

    private Profile() {
    }

    public static synchronized JsonObject root() {
        if (!loaded) {
            loaded = true;
            cached = new JsonObject();
            String path = System.getenv("ERP_AGENT_PROFILE");
            if (path != null && !path.isEmpty()) {
                try {
                    byte[] b = Files.readAllBytes(Paths.get(path));
                    cached = new JsonParser()
                            .parse(new String(b, Charset.forName("UTF-8"))).getAsJsonObject();
                } catch (Exception e) {
                    throw new IllegalStateException("ERP_AGENT_PROFILE 读取失败: " + path
                            + " (" + e.getMessage() + ")");
                }
            }
        }
        return cached;
    }

    /** 点路径取值："payDoc.payerAccountCode" -> String 或 null */
    public static String get(String path) {
        JsonElement e = walk(path);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    /** 点路径取对象："payDoc.headDefaults" -> JsonObject 或 null */
    public static JsonObject getObj(String path) {
        JsonElement e = walk(path);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    /** 点路径取数组："order.propagateHeadFields" -> JsonArray 或 null */
    public static com.google.gson.JsonArray getArr(String path) {
        JsonElement e = walk(path);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
    }

    /** 必填项：缺失时抛出带自助定位指引的异常 */
    public static String require(String path, String hint) {
        String v = get(path);
        if (v == null || v.isEmpty()) {
            throw new IllegalStateException("profile 缺少 " + path
                    + "（在 ERP_AGENT_PROFILE 文件中配置；" + hint + "）");
        }
        return v;
    }

    private static JsonElement walk(String path) {
        JsonElement cur = root();
        for (String k : path.split("\\.")) {
            if (cur == null || !cur.isJsonObject()) return null;
            cur = cur.getAsJsonObject().get(k);
        }
        return cur;
    }
}
