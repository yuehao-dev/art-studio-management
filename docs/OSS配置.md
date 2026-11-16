# 阿里云 OSS 配置

默认本地存储即可运行，OSS 为可选项。使用自己创建的 Bucket 和 RAM 用户，密钥保存在未提交的 `.env.local` 或服务器环境变量中。

```dotenv
STORAGE_PROVIDER=oss
OSS_ENDPOINT=https://oss-cn-beijing.aliyuncs.com
OSS_BUCKET=YOUR_BUCKET_NAME
OSS_ACCESS_KEY_ID=YOUR_RAM_ACCESS_KEY_ID
OSS_ACCESS_KEY_SECRET=YOUR_RAM_ACCESS_KEY_SECRET
```

Endpoint 按 Bucket 所在地域填写。将 Bucket 和对象设为私有，RAM 只授权所需对象操作。以下策略把范围限制为自己 Bucket 下的 studio/*，替换 YOUR_BUCKET_NAME 后绑定 RAM 用户：

```json
{
  "Version": "1",
  "Statement": [{
    "Effect": "Allow",
    "Action": ["oss:PutObject", "oss:GetObject", "oss:DeleteObject"],
    "Resource": ["acs:oss:*:*:YOUR_BUCKET_NAME/studio/*"]
  }]
}
```

对象 Key 由服务端生成：

```text
studio/avatars/YYYY/MM/用户ID/随机ID.jpg
studio/works/YYYY/MM/用户ID/随机ID.扩展名
studio/resources/YYYY/MM/用户ID/随机ID.扩展名
studio/attachments/YYYY/MM/用户ID/随机ID.扩展名
```

原始文件名仅用于显示和下载名，不直接用作对象路径。头像会经过解码、尺寸限制和重新编码。读取文件先验证业务权限，OSS 预览使用短时签名地址；不要将签名地址保存到 README 或公开日志中。

从本地切换到 OSS 只影响新上传文件，旧本地文件不会自动迁移，必须保留 uploads 和对应数据库记录。切换后重启后端，自行上传测试文件确认访问范围与下载行为。
