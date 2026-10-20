# agent（u8-agent 引擎）

U8C/NC 无头驱动：复用本机 UClient 已同步的客户端 jar（`~/UClient/apps/*/u8c_client_home/NCCACHE/<server>/CODE/lib`），
走官方客户端 RPC 通道 `ServiceDispatcherServlet`，用**个人账号**登录并调用服务端业务接口。

`stdin` 一行 JSON 请求 → `stdout` 一行 JSON 结果：`{"ok":true,"data":...}` 或 `{"ok":false,"error":"...","errorType":"..."}`

## 适用边界

- 仅适用于 **U8C / NC 系**（ServiceDispatcherServlet 协议栈）。不适用于 U8+、U8 cloud、YonSuite、NCC（未验证）。
- 需要本机已安装 UClient 且至少成功登录过一次（本地需有 NCCACHE 缓存）。
- 无需安装 JDK：编译用 `tools/ecj`，运行用 UClient 内置 JRE8。
- 每次运行 = 一次登录会话，占用一个并发 license；退出后会话随 JSESSIONID 失效。

## 环境变量

| 变量 | 必填 | 说明 |
|---|---|---|
| `ERP_AGENT_SERVER` | 是 | U8C 服务端基址，如 `http://<host>:<port>` |
| `ERP_AGENT_USER` | 是 | 个人登录账号 |
| `ERP_AGENT_PASSWORD` | 是 | 个人登录密码（只经环境变量传入，不落盘、不进日志） |
| `ERP_AGENT_ACCOUNT` | 否 | 账套编码，缺省取第一个可用账套 |
| `ERP_AGENT_CORP` | 否 | 登录公司编码，默认 `0001`（集团） |
| `ERP_AGENT_WORKDATE` | 否 | 业务日期 yyyy-MM-dd，默认当天 |
| `ERP_AGENT_PROFILE` | 否 | 本地档案 JSON 路径（写入链路的缺省值/字段映射） |
| `ERP_AGENT_CODE_DIR` | 否 | 客户端缓存 CODE 目录（缺省自动发现 jar 数最多的） |
| `ERP_AGENT_JAVA` | 否 | JRE8 java 路径（缺省 `~/UClient/share/java1.8.0_202-x64`） |
| `ERP_AGENT_ENV_FILE` | 否 | 可选 env 文件路径，启动时 source（默认 `~/.config/secrets.env`） |

## 构建与调用

```bash
./compile.sh                 # 编译（首次或改码后）
echo '{"action":"login"}' | ./u8-agent          # 登录冒烟
echo '{"action":"describe","service":"nc.itf.uap.sf.ILogin3Service"}' | ./u8-agent
```

## 动作一览

### 探测层（通用，不依赖 profile）

| action | 说明 |
|---|---|
| `accounts` | 列可用账套（匿名，不需登录凭据） |
| `login` | 登录并回显会话信息 |
| `describe` | 列接口/类的方法签名（本地反射，不联网） |
| `invoke` / `invokeAnon` | 通用远程调用（`service`/`method`/`argTypes`/`args`） |
| `genoid` | 生成单据主键（参数：`pkCorp`，可选 `count`） |
| `query` | 任意表只读查询：`{"table":"bd_measdoc","cols":[...],"where":"...","maxRows":200}` |
| `billTypes` | 探测本环境单据类型元数据表并列清单（多候选表自适应） |
| `billSpec` | 先例单据字段模板：arap 传 `billType`/`djbh` 拉全量 VO；通用表传 `table`+`cols`+`where` |

### 执行层

| action | 说明 |
|---|---|
| `billSave` | JSON→聚合 VO→平台 SAVE：`{"billType":"D3","parent":{...},"children":[...]}`；`dryRun:true` 只校验不落库；arap 内置 pkMap `vouchid`/`fb_oid`；落库主键以 `saveRet[0]` 为准（服务端可重编号），`djbh` 缺省自动取号 |
| `billAction` | 对既有 arap 单据执行动作：`{"op":"QUERY/EDIT/APPROVE/AUDIT/DELETE/UPDATE/INSPECT","vouchid"|"djbh"}`，billtype 自动解析 |
| `payAction` | `billAction` 旧名别名 |
| `createPurchaseOrder` | 创建采购订单（自由态），`{"head":{...},"items":[...]}` |
| `createPurchaseContract` | 创建采购合同（自由态，`billType` 默认 Z2），不传 `ct_code` 走服务端编号规则 |
| `orderFromContract` | 参照合同生成订单（CHG 转换），可选 `approve`+`prepay`，幂等可重跑 |
| `discardPurchaseOrder` | 废弃自由态/审核失败订单（DISCARD，dr=1） |
| `payEntry` | 「付款录入」卡片保存全链路（订单→付款单），银行字段自动解析 |

## profile.json（本地档案）

写入链路的公司私有缺省值，经 `ERP_AGENT_PROFILE` 指定。全部可选；缺了会在报错里告诉你查哪张表。

```json
{
  "corp": "1002",
  "payDoc": {
    "payerAccountCode": "<bd_bankaccbas.accountcode 我方付款账户>",
    "zjldwMeasName": "<bd_measdoc.measname 资金计量单位名>",
    "headDefaults": {"zyx6": "<付款类型自定义项值>"}
  },
  "order": {
    "purchaseOrgPk": "<采购组织 pk>",
    "arriveStoreOrg": {"<corpCode>": "<收货库存组织 pk>"},
    "bizTypeNameField": "vdef8",
    "propagateHeadFields": ["vdef4"]
  },
  "contract": {"bizTypeField": "def8", "sourceBillType": "Z2"},
  "bill": {"<djlxbm>": {"headDefaults": {}, "itemDefaults": {}}}
}
```

## 关键语义（排错高频）

- arap 单据（付款 fk / 报销 bx / 借款 jk / 应付 yf）共用 `nc.vo.ep.dj.DJZBVO`，
  头表 `arap_djzb`、体表 `arap_djfb`；头金额 `ybje/bbje/fbje`，行金额 `jfybje/jfbbje`、`dfybje/dfbbje`。
- `UPDATE/EDIT` **不写表体**——表体字段只能 SAVE 时定型；写错的单只能 DELETE（dr=1 软删）后重走 SAVE。
- classpath 必须排除 `cglib-*.jar`（NC 定制 XStream 误注册 CGLIBEnhancedConverter）。
- 登录后框架自动注入 `ClientEnvironment.configAccount`（FiPubDataCache 取数据源名用）。
