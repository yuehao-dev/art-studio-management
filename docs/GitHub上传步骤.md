# 上传到 GitHub

本目录是独立的公开源码副本，不包含原项目的 .env.local、work、真实上传文件、服务器私有配置或编译产物。不要把这些内容再从原项目复制回来。

## 推荐：GitHub Desktop

1. 登录 GitHub Desktop，选择 File → Add local repository，选择本项目目录。
2. 若提示目录不是 Git 仓库，选择 create a repository here，在当前目录初始化；不要重新生成 README、.gitignore 或许可证。
3. 检查 Changes 列表：应该只有源码、文档、示例配置、MIT LICENSE；确认没有凭据、上传文件或 work 目录。
4. 填写提交信息 `Initial open-source release`，点击 Commit to main。
5. 点击 Publish repository，名称建议 art-studio-management，先勾选 Keep this code private。
6. 查看 GitHub 上的文件和 README，无误后在 Settings → General → Danger Zone → Change repository visibility 改为 Public。

## 命令行方式

若熟悉 Git，在此目录执行以下命令。先在 GitHub 创建同名空仓库，不勾选自动生成 README、.gitignore 或 LICENSE，并复制其 HTTPS 地址。

```powershell
git init -b main
git add .
git status
git diff --cached --stat
git commit -m "Initial open-source release"
git remote add origin https://github.com/YOUR_GITHUB_USERNAME/art-studio-management.git
git push -u origin main
```

若 Git 要求身份，用自己的姓名或昵称和 GitHub 提供的 noreply 邮箱配置 `git config user.name` 与 `git config user.email`。GitHub 通过浏览器或凭据管理器登录，不使用 GitHub 登录密码直接推送，也不要将 Token 写进仓库地址或文件。

上传目录内的文件即可，GitHub 会自动生成源码 ZIP 下载；不需要把源码 ZIP 再提交进仓库。完成后可在 Releases 创建标签 v1.0.0，填写已实现功能、环境要求和已知限制。

许可证已按作者选择添加 MIT，版权署名为 Art Studio Management contributors；可在第一次提交前改成自己的 GitHub 昵称。公开前自行核对学校的毕业设计公开时间要求及所用素材权利；此副本不包含论文和学校原始文件。
