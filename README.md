# 清云OCR控制台

一个面向证件识别联调与效果验证的 OCR Demo，覆盖本地 CPU OCR 与多模型远程 OCR 对比场景。项目提供同步识别、异步队列、任务停止、SSE 实时通知、字段提取、JSON 输出，以及前端图片扰动测试能力，适合身份证、驾驶证、营业执照、护照等证件场景验证。

## 项目截图

![控制台截图](frontend/img.png)

## 功能概览

- 本地 OCR 引擎：`paddleocr-cpu`
- 远程 OCR 引擎：`qwen-vl`、`glm-ocr`、`paddleocr-vl`
- 同步阻塞识别：上传后直接返回识别结果
- 异步队列识别：任务入队、后台消费、前端自动刷新
- 任务停止：支持取消排队中或处理中任务
- SSE 实时通知：任务开始、完成、失败、取消事件实时推送
- 结果展示：任务信息、OCR 原始文本、字段提取、JSON 输出
- 图片扰动测试：模糊、倾斜、失真、遮挡、水渍、反光、褶皱
- 遮挡拖拽：可手动调整遮挡位置，100% 强度为纯黑不透明遮挡

## 技术栈

### 后端

- JDK 17
- Spring Boot 3.3.4
- Maven
- MyBatis-Plus
- MySQL
- SSE
- 本地 OCR：RapidOCR-Java 方案接入 Paddle 系 CPU 推理能力
- 远程 OCR：通过 Dify Workflow 接入 Qwen-VL、GLM-OCR、PaddleOCR-VL

### 前端

- Vue 3
- Vite
- 原生 CSS
- Canvas 图片扰动处理

## 引擎说明

### `paddleocr-cpu`

本地 CPU OCR 模式，适合离线联调和快速验证。当前 Java 侧实际接入的是 RapidOCR-Java 封装方案，用于调用 Paddle 系 OCR 推理能力。

### `qwen-vl` / `glm-ocr` / `paddleocr-vl`

通过 Dify Workflow 转发到远程大模型 OCR 工作流，便于和本地 CPU 方案做识别效果对比。

## 主要页面能力

### 上传识别

- 自定义引擎下拉选择
- 上传图片后实时预览
- 图片右侧悬浮操作面板
- 支持收起/展开面板
- 拖动强度滑块时面板自动降透明，便于观察图片变化

### 识别结果

- 任务信息
- OCR 原始文本
- 字段提取表格
- JSON 输出
- 同步识别 Loading 态
- 文本与 JSON 一键复制

### 队列与通知

- 任务队列滚动展示
- 实时通知滚动展示
- 点击任务可查看历史详情
- 支持停止当前任务

## 项目结构

```text
backend/   Spring Boot 后端
frontend/  Vue 3 + Vite 前端
Dockerfile 单镜像构建文件
```

## 本地开发

### 1. 数据库配置

数据库配置位于 [backend/src/main/resources/application.yml](backend/src/main/resources/application.yml)。

当前联调库示例：

```yaml
spring:
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://172.24.0.47:3306/tsingyun_platform_daas?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false
    username: root
    password: 123456
```

### 2. 启动后端

```powershell
& 'D:\3-env\apache-maven-3.6.3\bin\mvn.cmd' package '-Dmaven.repo.local=D:\devTools\qyztRepository' -DskipTests
java -jar .\backend\target\paddleocr-demo-0.0.1-SNAPSHOT.jar --server.port=18081
```

### 3. 启动前端

```powershell
cd frontend
& 'C:\Program Files\nodejs\npm.cmd' install
& 'C:\Program Files\nodejs\npm.cmd' run dev
```

默认前端地址：`http://127.0.0.1:5173`

### 4. 生产静态资源同步

前端构建：

```powershell
cd frontend
& 'C:\Program Files\nodejs\npm.cmd' run build
```

将构建产物同步到后端静态目录时，应复制 `dist` 目录中的内容，而不是复制整个 `dist` 目录：

```powershell
$src = 'D:\1-workspace\5-github\PaddleOCR-JAVA\frontend\dist\*'
$dest = 'D:\1-workspace\5-github\PaddleOCR-JAVA\backend\src\main\resources\static'
Copy-Item -Path $src -Destination $dest -Recurse -Force
```

正确结构应为：

```text
backend/src/main/resources/static/index.html
backend/src/main/resources/static/assets/...
```

## 核心接口

- `POST /api/ocr/sync` 同步识别
- `POST /api/ocr/async` 异步识别
- `POST /api/ocr/tasks/{taskNo}/cancel` 停止任务
- `GET /api/ocr/tasks` 最近任务列表
- `GET /api/ocr/tasks/{taskNo}` 任务详情
- `GET /api/ocr/queue` 队列状态
- `GET /api/ocr/notifications` 通知列表
- `GET /api/ocr/notifications/stream` SSE 通知流

## Docker 镜像

镜像地址：`harbor.tsingyun.net/platform/ai-ocr-demo:1.0`

### 本地构建

```powershell
docker build -t harbor.tsingyun.net/platform/ai-ocr-demo:1.0 .
```

### 推送 Harbor

```powershell
docker login harbor.tsingyun.net -u admin -p "Tsingyun@2024"
docker push harbor.tsingyun.net/platform/ai-ocr-demo:1.0
```

### 服务器启动示例

```bash
docker run -d \
  --name ai-ocr-demo \
  --restart always \
  -p 8083:8083 \
  -e SERVER_PORT=8083 \
  -e SPRING_DATASOURCE_URL='jdbc:mysql://172.24.0.47:3306/tsingyun_platform_daas?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false' \
  -e SPRING_DATASOURCE_USERNAME='root' \
  -e SPRING_DATASOURCE_PASSWORD='123456' \
  -v /data/ai-ocr-demo/uploads:/app/backend/uploads \
  harbor.tsingyun.net/platform/ai-ocr-demo:1.0
```

访问地址：`http://服务器IP:8083`

## 当前验证状态

- 前端构建通过
- 后端 Maven 打包通过
- 同步识别可用
- 异步识别、队列刷新、实时通知可用
- 任务停止可用
- JSON 输出可用
- 前端图片扰动测试可用
- Harbor 镜像支持构建与推送