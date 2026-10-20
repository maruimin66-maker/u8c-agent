# 排错速查

| 现象 | 根因 | 处理 |
|---|---|---|
| `CGLIBEnhancedConverter` / `ClassNotFoundException: org.objectweb.asm.CodeVisitor` | classpath 混入 `cglib-nodep*.jar`，NC 定制 XStream 误注册 | 启动器已排除 `cglib-*`；自查 `ERP_AGENT_CODE_DIR` 指向 |
| `FiPubDataCache` 数据源名为空 | 登录后未注入 `ClientEnvironment.configAccount` | 引擎已在 login 内自动注入；仍现则查账套数据源配置 |
| 「付款银行账号为空」 | 表体 `bfyhzh` 未填我方账户 pk | 填 `bd_bankaccbas.pk`（本公司付款户） |
| 下游推送/接口报「客户银行账号全称查不到」 | `fkyhmc` 填的不是对方开户行**全称**（或填成我方值） | `fkyhmc`=对方 `bd_bankdoc.bankdocname` 全称 |
| `pj_jsfs` 落库为空 | `bd_balatype` 挂在集团层，按公司过滤查不到 | 用 `pk_corp=0001`（或不过滤）查 |
| `queryOrderVOByKey` 返回 null | 传了表体行 pk | 用订单**头** pk |
| 「超出本币价税合计」 | 旧付款单未软删，占用可付额度 | `billAction DELETE` 旧单后重跑 |
| 表体字段改了没落库 | `UPDATE/EDIT` 不写表体 | 只能 SAVE 定型；写错 → DELETE 重建 |
| `auditOneArapBill` invalid UFDate | 单审接口日期参数差异 | 用 `AUDIT`（auditArapBill）代替 `AUDITONE` |
| 单据号落成临时前缀而非正式编号 | 显式传了编号/`autoBillCode:false`，绕过编号规则 | 删单重建，不传编号走服务端规则 |
| `operdate` 不能为 NULL | `ct_manage` 必填 | 引擎内置默认当天 |
| `getBillCode` 编号段渲染成 `**` | objVO 缺规则对象值；规则对象元素要用**中文名**作 key | 例 `"公司":<pk_corp>`（fetchBillCode 已内置） |
| 字段写了没效果、回显为 null | 字段名写错/写错层级（头/体） | `setAttributeValue` 对未知属性静默无效；对照 billSpec 先例与返回 `vo` 回显 |
| `UClient cache not found` | 本机无 UClient 缓存（未登录过）或路径不符 | 先登录一次 UClient；或设 `ERP_AGENT_CODE_DIR`/`ERP_AGENT_JAVA` |
| `missing ERP_AGENT_SERVER` | 未设服务端基址 | `export ERP_AGENT_SERVER=http://<host>:<port>` |
| 长时间无响应/连接超时 | 内网/VPN 未连，或服务端地址错 | `nc -z <host> <port>` 验证连通性 |
| `accounts` 空 | 密码错误或账号无权 | 核对凭据；该调用不需登录态即可验证 |
| `login` 返回「已在线」 | 并发会话冲突 | 引擎自动强制登录重试；频繁互踢查是否有其他会话 |
| 未知 VO 类 billSave 主键没补 | pkMap 仅 arap 内置 | 显式传 `pkMap` 或在字段里自带主键 |
| `billTypes` 全部候选失败 | 该版本元数据表名/列名不同 | 用返回的已试清单 + `describe`/`query` 定位后提 issue |
