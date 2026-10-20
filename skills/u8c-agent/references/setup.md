# 环境与 profile 配置

## 前置条件

1. **UClient 已安装且登录过**：引擎复用 `~/UClient/apps/*/u8c_client_home/NCCACHE/<server>/CODE/lib` 下的客户端 jar。从未登录过的机器没有缓存。
   - 非默认路径 / Windows：手动设 `ERP_AGENT_CODE_DIR` 与 `ERP_AGENT_JAVA`。
2. **网络可达 U8C 服务端**（`ServiceDispatcherServlet`，通常内网或 VPN）。
3. **个人 ERP 账号**（有目标单据的制单权限；license 并发占 1）。

## 环境变量

| 变量 | 必填 | 说明 |
|---|---|---|
| `ERP_AGENT_SERVER` | 是 | `http://<u8c-host>:<port>` |
| `ERP_AGENT_USER` / `ERP_AGENT_PASSWORD` | 是 | 个人账号；经 env 传入不落盘 |
| `ERP_AGENT_ACCOUNT` | 否 | 账套编码，缺省取第一个可用账套 |
| `ERP_AGENT_CORP` | 否 | 登录公司编码，默认 `0001` |
| `ERP_AGENT_WORKDATE` | 否 | 业务日期，默认当天 |
| `ERP_AGENT_PROFILE` | 否 | profile.json 绝对路径 |
| `ERP_AGENT_CODE_DIR` | 否 | NCCACHE CODE 目录（缺省自动发现 jar 最多的） |
| `ERP_AGENT_JAVA` | 否 | JRE8 路径（缺省 UClient 内置） |
| `ERP_AGENT_ENV_FILE` | 否 | 启动时 source 的 env 文件（默认 `~/.config/secrets.env`） |

凭据管理建议：放 `~/.config/secrets.env` 或任意本地 env 文件，`ERP_AGENT_*` 变量不落代码、不进对话。

## profile.json 字段

模板：`assets/profile.example.json`。全部字段可选；每项缺省时对应链路的行为如下。

| 路径 | 用途 | 缺失行为 |
|---|---|---|
| `corp` | 写入链路默认公司 pk | payEntry/billSave 需显式传 corp 或用登录公司 |
| `payDoc.payerAccountCode` | 我方付款账户（`bd_bankaccbas.accountcode`） | payEntry 报错并提示查询方法 |
| `payDoc.zjldwMeasName` | 资金计量单位名（`bd_measdoc.measname`） | 不填 zjldw |
| `payDoc.headDefaults` | 付款单表头自定义项默认值（如 `zyx6`） | 不填 |
| `order.purchaseOrgPk` | 采购组织 pk（订单头 `cpurorganization`） | 不填 |
| `order.arriveStoreOrg.<corp>` | 收货库存组织 pk（按公司分键） | 不填 |
| `order.bizTypeNameField` | 业务类型名回写到的订单自定义项（如 `vdef8`） | 不回写 |
| `order.propagateHeadFields` | 订单表头透传到表体的自定义项名单 | 不透传 |
| `contract.bizTypeField` | 合同上记录业务类型名的字段（默认 `def8`） | 用 `def8` |
| `contract.sourceBillType` | 合同单据类型码（默认 `Z2`） | 用 `Z2` |
| `bill.<djlxbm>.headDefaults` / `itemDefaults` | 指定单据类型的保存默认值 | 无默认 |

profile 值怎么找（探测法）：

```bash
# 我方付款账户
./u8-agent <<< '{"action":"query","table":"bd_bankaccbas","cols":["pk_bankaccbas","accountcode","accountname"],"where":"pk_corp='"'"'<corp>'"'"'"}'
# 计量单位 / 结算方式 / 存货 / 客商
{"table":"bd_measdoc","cols":["pk_measdoc","meascode","measname"]}
{"table":"bd_balatype","cols":["pk_balatype","balancode","balanname"]}   # 注意：常挂集团层 pk_corp=0001
{"table":"bd_invmandoc","cols":["pk_invmandoc","pk_invbasdoc","invcode","invname"]}
{"table":"bd_cumandoc","cols":["pk_cumandoc","pk_cubasdoc","custcode","custname"]}
```

## 验证 profile 生效

改完 profile 无需重启（每次进程启动时读一次）。用 `payEntry` 或 `billSave` dryRun 验证：

```bash
export ERP_AGENT_PROFILE=/path/to/profile.json
echo '{"action":"billSave","billType":"D3","dryRun":true,"parent":{...},"children":[...]}' | ./u8-agent
```
