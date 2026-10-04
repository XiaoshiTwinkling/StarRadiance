# 多版本工程说明（Stonecutter）

本仓库用 [Stonecutter](https://stonecutter.kikugie.dev/) 做多版本适配：**一份共享源码**
（根目录 `src/`）编译出多个 Minecraft 版本，版本之间的差异用注释条件、版本化属性或资源变体表达。

当前注册的版本：**1.21.1**（提交态 / vcs version）、**1.21.4**、**1.21.8**。

## 1. 工程结构

```
settings.gradle               Stonecutter 插件与版本清单
stonecutter.gradle.kts        项目控制器：当前激活版本 + buildAll / verifyAll 聚合任务
build.gradle                  共享构建脚本，每个版本各跑一次
gradle.properties             与版本无关的项（mod 信息、loom 版本）
versions/<版本>/gradle.properties   该版本的依赖与依赖范围
src/                          共享源码与资源（含 //? 条件注释）
src/client/resources/shader_variants/<着色器时代>/...   按版本选择的整套文件
docs/VERSIONING.md            本文档
```

两点容易踩坑的约定：

- 共享构建脚本保持 **Groovy**（`centralScript = 'build.gradle'`），但控制器必须是
  **Kotlin**（`stonecutter.gradle.kts`）：控制器要先 `plugins { id("dev.kikugie.stonecutter") }`
  才能拿到 `stonecutter` 扩展，Groovy 控制器在 0.9.8 下拿不到它（已实测）。
- `gradle.properties` 里的 `dev.kikugie.stonecutter.hard_mode=true` 是对"使用 Groovy
  构建脚本"这一选择的确认，用来消除构建警告。

## 2. 每版本属性契约

`versions/<版本>/gradle.properties` 必须提供：

| 键 | 含义 | 例 |
|---|---|---|
| `deps.minecraft` | Minecraft 版本 | `1.21.4` |
| `deps.yarn` | Yarn 映射 | `1.21.4+build.8` |
| `deps.loader` | Fabric Loader | `0.19.5` |
| `deps.fabric_api` | Fabric API 版本 | `0.119.4+1.21.4` |
| `dep.minecraft` | 写进 `fabric.mod.json` 的依赖范围 | `~1.21.4` |

新增一个版本 = 新建 `versions/<版本>/gradle.properties`，并在 `settings.gradle` 的
`versions '...'` 列表里加上版本号。产物命名为 `starradiance-<mod_version>+<mc 版本>.jar`。

## 3. 常用命令

```bash
./gradlew tasks --all                       # 查看 Stonecutter 提供的版本切换任务
./gradlew "Set active project to 1.21.4"    # 切换激活版本（就地改写注释与控制器）
./gradlew "Refresh active project"          # 重新处理激活版本的注释
./gradlew "Reset active project"            # 切回 1.21.1（提交前务必执行）
./gradlew ":1.21.1:build"                   # 构建单个版本
./gradlew ":1.21.1:verifyEphemeris"         # 星历回归
./gradlew ":1.21.1:checkLangKeys"           # 翻译键一致性
./gradlew buildAll verifyAll                # 全部版本（未移植的版本会失败，见下）
```

版本切换会**就地重写** `src/` 里的条件注释，并改写控制器里的 `stonecutter active "..."`，
所以提交前必须 `Reset active project`；`git status` 干净即代表共享源码处于 1.21.1 状态。

> [!IMPORTANT]
> **手写条件注释之后，必须先 `Refresh active project` 再编译当前激活版本。**
> 共享源码里条件块的状态是按"激活版本"维护的：新增的 `//? if` 块在你当前激活的版本下
> 需要 Stonecutter 处理一次（该注释的注释、该展开的展开）。直接编译会出现一堆莫名其妙的
> "找不到符号"，其实只是状态没刷新。构建**非**激活版本的节点不受影响（它读的是生成源码）。

## 4. 版本条件写法

```java
//? if >=1.21.5 {
/*SkyRendering.renderSky(...);
*///?} else {
worldRenderer.renderSky(...);
//?}
```

- 作用域：`{ ... //?}` 包住多行；不写花括号时只作用于下一非空行。
- 分支：`//? elif` / `//? else`。
- 小改动优先用 Stonecutter 的 `swaps`（把构建脚本里的值注入源码）和 `replacements`，
  避免到处铺条件注释。
- 注释只写在**共享源码**里；`versions/<版本>/build/generated/` 是生成物，不要手改。

整套文件（无法用注释处理的 JSON 等）走**文件变体**：放在
`src/client/resources/shader_variants/<时代>/...`，由 `build.gradle` 里的
`processClientResources` 按版本范围只拷入匹配的那一份。当前时代划分为
`1.21.1`（对应 `<1.21.5`）与将来的 `1.21.5`（`>=1.21.5`，渲染管线重写后需要新建）。
Stonecutter 本身还能预处理 `.java`、`.json5`、`.fsh`、`.vsh`（C 风格注释）与
`.yml` / `.properties` 等（`#` 注释），所以着色器的 **GLSL** 部分也允许直接用 `//? if`；
只有 `.json` 这类文件必须走上面两种机制。

## 5. 单版本移植流程

1. `./gradlew "Set active project to <版本>"`（或实测得到的等价任务名）。
2. `./gradlew ":<版本>:build"`，按编译错误逐个修：能用条件注释就用，整文件差异用资源变体。
3. `./gradlew ":<版本>:verifyEphemeris" ":<版本>:checkLangKeys"` 必须全绿。
4. 处理版本专属资源（核心着色器、mixin 配置等），确认 `versions/<版本>/build/libs/` 里的 jar
   内容正确（用 `jar tf` 对比条目清单）。
5. 游戏内冒烟测试：夜空/日月、K 星图、U 星图、配置界面、Iris 光影各过一遍。
6. 切回 1.21.1 并确认 `git status` 干净、1.21.1 的 `build` / 两个校验任务仍全绿。
7. 提交；CI 矩阵里对应版本从 `continue-on-error` 改为严格模式。

## 6. 已知断裂点清单（1.21.5 是大头）

按模块列出移植时最可能撞到的地方，越靠前越先修：

| 模块 | 断裂点 |
|---|---|
| 渲染管线 | `RenderSystem.setShader` / `ShaderProgram` / 核心着色器 JSON 格式在 1.21.5 被 `RenderPipeline` 体系取代；`VertexBuffer` → `GpuBuffer`，`Tessellator` / `BufferBuilder` → `RenderPass` |
| 天空 | `WorldRenderer.renderSky`（1.21.5 起拆到 `SkyRendering`，签名与 `FrameGraphBuilder` 相关）、`ClientWorld.getSkyColor` / `getSkyBrightness` / `getStarBrightness` |
| 光照 | `LightmapTextureManager.update` 与 `easeOutQuart`（1.21.2 起实现变化） |
| GUI | `DrawContext` 绘制方法、`Screen` 生命周期、`I18n` / `TextRenderer` 细节 |
| 数据 | Fabric 数据附件 API（1.20.5+ 才有，1.21.x 内基本稳定）、`ServerWorld` 相关签名 |
| 资源 | 核心着色器 JSON（见第 4 节）、mixin 配置的 `compatibilityLevel` |

每个版本的正式判定标准 = 编译通过 + 两个校验任务全绿 + 游戏内冒烟通过。

## 7. 当前状态

- 1.21.1：基线，`build` / `verifyEphemeris` / `checkLangKeys` 全绿。
- 1.21.4：**已移植完成**，三个任务同样全绿；产物 `starradiance-1.0.0+1.21.4.jar`。
- 1.21.2 / 1.21.3：**已移植完成**，三个任务同样全绿；产物 `starradiance-1.0.0+1.21.2.jar` 与
  `+1.21.3.jar`。
- 1.21.8：仅完成接线（依赖、属性、构建节点），代码尚未移植；CI 对应矩阵项仍 `continue-on-error`。
- 成品统一放仓库根目录的 `Result/`（已 gitignore）：1.21.1 ~ 1.21.4 四个 jar 都在里面。

### 版本代际的真实边界（本次实测修正）

原先按 1.21.4 划分的两条边界，实测后各自前移/保持：

| 变更 | 实际从哪个版本开始 |
|---|---|
| 渲染新 API（`ShaderProgramKey`、`ShaderProgramKeys`、`GlUsage`、`Fog`、`SimpleFramebuffer` 三参数、`RenderSystem.setShader`） | **1.21.2** |
| 核心着色器 JSON 命名空间化（`starradiance:core/sky`） | **1.21.2** |
| 纹理 API（`ReloadableTexture` + `loadContents`、`TextureManager#getTexture`） | **1.21.4** |

所以 Java 里的渲染条件写成 `//? if >=1.21.2`，而 `MoonTextures` 的条件保持 `>=1.21.4`。

### 数据附件在 1.21.2 上的特殊处理

1.21.2 只能配 Fabric API 0.106.1，它的附件 API 既没有 `create(Identifier, Consumer)` 也没有
`syncWith`。`WorldEpoch` 因此按构建常量 `attachment_sync`（在 `build.gradle` 里定义为
"除了 1.21.2 都为真"）分支：

- 有 sync：`create(id, builder -> builder.persistent(...).syncWith(...))`，`world.getAttached/setAttached`
- 无 sync（仅 1.21.2）：`builder().persistent(...).buildAndRegister(id)`，并通过
  `(AttachmentTarget) world` 读写；历元仍随存档持久化，但客户端改为本地推导，不再由服务端同步

### 1.21.4 实测确认的 API 变更（1.21.5+ 多半会再变一次）

| 旧写法（1.21.1） | 1.21.4 写法 |
|---|---|
| `RenderSystem.setShader(() -> program)` | `RenderSystem.setShader(program)` |
| `GameRenderer.getPositionColorProgram()` 等 | `ShaderProgramKeys.POSITION_COLOR` / `POSITION_TEX` / `POSITION` |
| Fabric `CoreShaderRegistrationCallback` | 自定义 `ShaderProgramKey(Identifier.of(ns, "core/name"), VertexFormats…, Defines.EMPTY)`，见 `CoreShaderKeys` |
| `new VertexBuffer(VertexBuffer.Usage.STATIC)` | `new VertexBuffer(GlUsage.STATIC_WRITE)`（注意不是 `STATIC`） |
| `BackgroundRenderer.clearFog()` | `RenderSystem.setShaderFog(Fog.DUMMY)` |
| `TextureManager.getOrDefault(id, null)` | `TextureManager.getTexture(id)` |
| `new SimpleFramebuffer(w, h, false, false)` | `new SimpleFramebuffer(w, h, false)` |
| `AbstractTexture#load(ResourceManager)` | 继承 `ReloadableTexture(Identifier)` 并实现 `loadContents(ResourceManager)` |
| 核心着色器 JSON `"vertex": "starradiance:sky"` | `"starradiance:core/sky"`（见 `shader_variants/1.21.4/`） |
