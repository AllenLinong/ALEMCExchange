# ALEMCExchange 构建教程

本教程将指导你如何从源代码构建 ALEMCExchange 插件。

## 前提条件

在开始之前，确保你已经安装了以下软件：

1. **Java Development Kit (JDK)** 17 或更高版本
   - 下载链接：[Oracle JDK](https://www.oracle.com/java/technologies/downloads/) 或 [OpenJDK](https://openjdk.org/)
   - 安装后请确保 `java` 和 `javac` 命令在系统 PATH 中

2. **Maven** 3.6.0 或更高版本
   - 下载链接：[Apache Maven](https://maven.apache.org/download.cgi)
   - 安装后请确保 `mvn` 命令在系统 PATH 中

3. **Git**（可选）
   - 用于克隆仓库
   - 下载链接：[Git](https://git-scm.com/downloads)

## 步骤 1：获取源代码

### 方法 A：克隆仓库（推荐）

```bash
# 克隆仓库
git clone https://github.com/yourusername/ALEMCExchange.git

# 进入目录
cd ALEMCExchange
```

### 方法 B：下载 ZIP 文件

1. 访问 GitHub 仓库页面
2. 点击 "Code" 按钮
3. 选择 "Download ZIP"
4. 解压 ZIP 文件到本地目录
5. 进入解压后的目录

## 步骤 2：构建项目

在项目根目录执行以下命令：

```bash
# 清理并构建项目
mvn clean package -DskipTests
```

这个命令会：
1. 清理之前的构建结果
2. 编译源代码
3. 打包成 JAR 文件
4. 跳过测试（可选）

## 步骤 3：获取构建产物

构建成功后，JAR 文件会生成在 `target` 目录中：

- `target/ALEMCExchange-1.1.2.jar` - 主插件文件
- `target/original-ALEMCExchange-1.1.2.jar` - 未混淆的 JAR 文件（用于调试）

## 步骤 4：安装插件

1. 停止你的 Minecraft 服务器
2. 将 `ALEMCExchange-1.1.2.jar` 复制到服务器的 `plugins` 文件夹
3. 启动服务器
4. 插件会自动创建配置文件

## 步骤 5：配置插件

服务器启动后，会在 `plugins/ALEMCExchange` 目录中生成以下配置文件：

- `config.yml` - 核心配置
- `items.yml` - 物品配置
- `lang.yml` - 语言配置

根据你的服务器需求修改这些配置文件。

## 常见问题

### 构建失败

如果构建失败，请检查：

1. **JDK 版本**：确保使用 JDK 17 或更高版本
2. **Maven 版本**：确保使用 Maven 3.6.0 或更高版本
3. **网络连接**：Maven 需要下载依赖，确保网络连接正常
4. **依赖冲突**：检查是否有依赖冲突

### 插件加载失败

如果插件加载失败，请检查：

1. **服务器版本**：确保服务器版本与插件兼容（1.21+）
2. **依赖插件**：确保安装了必要的依赖插件（如 PlaceholderAPI，可选）
3. **配置文件**：检查配置文件是否正确
4. **错误日志**：查看服务器日志中的错误信息

## 开发环境设置

如果你想在 IDE 中开发插件：

### IntelliJ IDEA

1. 打开 IntelliJ IDEA
2. 选择 "Open" 并导航到项目目录
3. IDEA 会自动识别 Maven 项目
4. 等待依赖下载完成
5. 开始开发

### Eclipse

1. 打开 Eclipse
2. 选择 "File" > "Import"
3. 选择 "Maven" > "Existing Maven Projects"
4. 导航到项目目录并选择
5. 等待依赖下载完成
6. 开始开发

## 测试

要运行测试，请执行：

```bash
mvn test
```

## 生成 API 文档

要生成 Javadoc 文档，请执行：

```bash
mvn javadoc:javadoc
```

文档会生成在 `target/site/apidocs` 目录中。

## 贡献

如果你想贡献代码：

1. Fork 仓库
2. 创建一个新的分支
3. 提交你的更改
4. 发起 Pull Request

## 联系

如果有任何问题，请通过以下方式联系：

- GitHub Issues：[ALEMCExchange Issues](https://github.com/yourusername/ALEMCExchange/issues)
- Discord：[Discord 服务器](https://discord.gg/yourserver)