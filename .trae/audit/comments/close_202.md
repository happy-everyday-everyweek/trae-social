## 关闭：已修复（与 #158 重复）

经审查，`app/src/main/java/com/trae/social/app/ui/SocialBottomBar.kt` L154-L254 的 Tab 颜色、选中圆点缩放、按压弹簧均已从 MediumBouncy 改为 NoBouncy 或 LowBouncy + 适当 stiffness，并在 reduceMotion 下走非弹跳分支。与 #158 是同一问题的英文重复 issue，已同步修复。满足全部 acceptance criteria。

> 本评论由 issue 审查自动化任务（review-closed-issues 分支）基于当前 main 代码核对后生成。
