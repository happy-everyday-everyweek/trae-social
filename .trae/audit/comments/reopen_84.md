## 重新打开：头像 hash 碰撞根本问题未解决

经审查，`feature-profile/src/main/java/com/trae/social/profile/ProfileUtils.kt` L18-L29 与 `feature-feed/src/main/java/com/trae/social/feed/FeedUtils.kt` 的 `avatarUriFromSeed` 仍使用 8 类别 × 25 张 = 200 张图。代码注释声称"使用 seedHash 高低位组合分布更均匀"，但实际代码 `(seedHash and 0x7FFFFFFF) % 25` 仅清除符号位后取模 25，与原 `((seedHash % 25) + 25) % 25` 数学等价——鸽巢原理保证 221 账号 > 200 张图必有 21+ 碰撞，未真正缓解。

issue 建议的三种修复方向均未实施：
1. 扩充资源至 250+ 张（未做）
2. 启用 `avatars/index.txt` 显式映射（仍未读取，参见 #83）
3. 用 accountId 派生唯一索引（未做）

请择一方向落地后再次关闭。

> 本评论由 issue 审查自动化任务（review-closed-issues 分支）基于当前 main 代码核对后生成。
