# 把本项目推送到 GitHub（本地操作）

本仓库已打包为 `MockGPS.bundle`（含完整提交历史，不含任何 token / APK / 构建产物）。
在**你自己能正常访问 GitHub 的电脑**上执行下面步骤即可，token 不经过任何第三方。

## 前置：在 GitHub 建好空仓库

> **若你已在 GitHub 上建好 `LYJ2025/MockGPS` 空仓库，这整步可跳过，直接看下方「推送步骤」。**
> 还没建的话按下面做：

1. 打开 https://github.com/new
2. Repository name 填 `MockGPS`，选 **Public**
3. **不要**勾 Add a README / .gitignore / license（勾了会和 bundle 冲突）
4. 点 **Create repository**

> 仓库归 `LYJ2025` 账号，最终地址是 https://github.com/LYJ2025/MockGPS

## 推送步骤（命令行）

```bash
# 1. 把 MockGPS.bundle 传到本机后，克隆出代码（从 bundle 克隆，不连网）
git clone MockGPS.bundle MockGPS

# 2. 进入目录
cd MockGPS

# 3. 确认在 main 分支（bundle 已带 refs/heads/main，克隆后即为 main，无需再手动建分支）
git branch --show-current

# 4. 把远程指向你刚建的空仓库
git remote set-url origin https://github.com/LYJ2025/MockGPS.git

# 5. 推送
git push -u origin main
```

第 4 步会提示输入账号密码：
- **用户名**：填 `LYJ2025`
- **密码**：填你的 GitHub **Personal Access Token**（不是 GitHub 登录密码）
  - 如果是 fine-grained token，push 时用 token 作为密码即可
  - 没有 token？去 https://github.com/settings/tokens 生成一个（勾 `repo` 权限）

推送成功后访问 https://github.com/LYJ2025/MockGPS 即可看到代码。

## 想顺手把 APK 也放上去（可选）

源码仓库默认不含 APK（被 `.gitignore` 排除，符合常规）。如果想让用户直接在仓库下载安装包：

1. 在 GitHub 仓库页点 **Releases → Draft a new release**
2. Tag 填当前版本号（如 `v1.11`），标题 `虚拟定位助手 v1.11`
3. 把对应的 `app-debug-v<版本号>.apk` 拖进去作为附件
4. 发布

## 备注

- 仓库的提交作者已设为 **`LYJ2025`**（noreply 邮箱），与你的 GitHub 账号一致，push 后在贡献图里会正确归属到你。
- 沙箱内所有代码均已 `git commit`，`MockGPS.bundle` 就是这份状态的完整快照。
