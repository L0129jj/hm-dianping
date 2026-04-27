# hm-dianping

一个基于 Spring Boot + Redis + MySQL 的本地生活点评项目，包含商铺查询、探店笔记、关注推送、优惠券秒杀等核心能力。项目内置前端静态页面（Nginx 托管）和后端 API（Spring Boot）。

## 功能特性

- 手机号验证码登录、Token 刷新与登出
- 商铺缓存查询（缓存穿透防护）
- 商铺类型查询与附近商铺 GEO 查询
- 探店笔记发布、点赞、点赞排行榜
- 关注/取关、共同关注、关注流分页滚动查询
- 优惠券查询与秒杀下单（Lua + Redis Stream + 异步消费）
- 用户签到与连续签到统计（Bitmap）
- 图片上传与删除（对接 Nginx 静态目录）

## 技术栈

- Java 21
- Spring Boot 3.2.8
- MyBatis-Plus 3.5.5
- MySQL
- Redis（String/Hash/ZSet/Geo/Bitmap/Stream）
- Redisson
- Nginx（前端静态资源与反向代理）
- Hutool

## 项目结构

```text
hm-dianping/
├─ src/main/java/com/hmdp
│  ├─ controller        # 接口层
│  ├─ service/impl      # 业务实现
│  ├─ mapper            # MyBatis Mapper
│  ├─ config            # MVC/Redis/MyBatis 配置
│  └─ utils             # 缓存、拦截器、ID 生成等工具类
├─ src/main/resources
│  ├─ application.yaml  # 应用配置
│  ├─ db/hmdp.sql       # 初始化 SQL
│  ├─ mapper            # XML SQL
│  └─ seckill.lua       # 秒杀 Lua 脚本
└─ nginx-1.18.0         # 前端静态资源 + Nginx
```

## 快速开始

### 1) 环境准备

- JDK 21
- Maven 3.9+
- MySQL 5.7+/8.x
- Redis 6.x+
- Windows（项目内已提供 `nginx-1.18.0`）

### 2) 初始化数据库

在 MySQL 中创建库并导入脚本：

```sql
CREATE DATABASE `hm-dianping` DEFAULT CHARACTER SET utf8mb4;
USE `hm-dianping`;
SOURCE D:/Java/hm-dianping/src/main/resources/db/hmdp.sql;
```

> 如果 `SOURCE` 路径不可用，请在你的 MySQL 客户端手动执行 `hmdp.sql`。

### 3) 配置后端

编辑 `src/main/resources/application.yaml`：

- `spring.datasource.url`
- `spring.datasource.username`
- `spring.datasource.password`
- `spring.data.redis.host`
- `spring.data.redis.port`
- `spring.data.redis.password`

当前默认后端端口为 `8081`。

### 4) 检查图片上传目录

`SystemConstants.IMAGE_UPLOAD_DIR` 当前配置见：

- `src/main/java/com/hmdp/utils/SystemConstants.java`

请确认该路径指向你本机 Nginx 静态资源目录（例如 `.../nginx-1.18.0/html/hmdp/imgs/`），否则图片上传后前端可能无法访问。

### 5) 启动后端

在项目根目录执行：

```powershell
mvn spring-boot:run
```

### 6) 启动 Nginx

项目自带 Nginx 配置中：

- 前端访问端口：`8080`
- `/api` 反向代理到：`http://127.0.0.1:8081`

启动命令（Windows）：

```powershell
D:\Java\hm-dianping\nginx-1.18.0\nginx.exe
```

访问：`http://localhost:8080`

## 关键接口（示例）

- 用户
  - `POST /user/code` 发送验证码
  - `POST /user/login` 登录
  - `POST /user/logout` 登出
  - `POST /user/sign` 签到
  - `GET /user/sign/count` 连续签到统计
- 商铺
  - `GET /shop/{id}` 查询商铺详情
  - `GET /shop/of/type` 按类型/坐标分页查询
- 笔记
  - `GET /blog/hot` 热门笔记
  - `PUT /blog/like/{id}` 点赞/取消点赞
  - `GET /blog/of/follow` 关注流滚动分页
- 秒杀
  - `POST /voucher-order/seckill/{id}` 秒杀下单

## 秒杀链路说明

秒杀核心流程在 `VoucherOrderServiceImpl` + `seckill.lua`：

1. Lua 脚本原子校验库存与一人一单
2. 校验通过后写入 `stream.orders`
3. 后台单线程消费者组读取 Stream
4. Redisson 分布式锁 + DB 扣减库存 + 创建订单
5. 异常场景处理 pending-list

> 首次使用 Redis Stream 时，需要确保消费者组存在（如 `g1` / `c1`）。

## 开发说明

- 登录拦截放行路径见 `src/main/java/com/hmdp/config/WebMvcConfigurer.java`
- Nginx 配置见 `nginx-1.18.0/conf/nginx.conf`
- Redis Key 常量见 `src/main/java/com/hmdp/utils/RedisConstants.java`

## 常见问题

- 图片上传成功但页面不显示
  - 检查 `SystemConstants.IMAGE_UPLOAD_DIR` 是否与本机 Nginx 目录一致。
- 前端请求 404 或联调失败
  - 检查是否通过 `http://localhost:8080` 访问，以及 `/api` 是否正确代理到 `8081`。
- 秒杀接口异常
  - 检查 Redis 是否启动、消费者组是否创建、Lua 脚本命令拼写是否正确。

## 测试

```powershell
mvn test
```

## License

本项目仅用于学习与交流，商用请根据实际情况补充许可证与合规说明。
