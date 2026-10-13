# Handoff：BM25 倒排索引补真 + GLM 配置切换（已完成）

> 移交时间 2026-10-13。工作在 `main` 分支工作区，**未提交**。接手会话从「待办」按序执行，每步以完成标准收口。
> **状态更新 2026-10-13**：待办 1–3 已全部完成并随本次提交入库（待办 4 完成文档部分），审查发现并修复：`loadFrom` 补回 `type=segment` 过滤（整篇文档点不进 BM25 通道，保住 UNION 锚点语义）、空分段分支不进关键词通道。本文档仅作历史记录。

## 已完成（17 个单测全绿，勿重做）

| 文件 | 状态 |
|---|---|
| `ai-service/.../rag/InvertedIndex.java` | 新增。内存倒排索引 + BM25（CJK bigram 分词、真实 DF/IDF、k1=1.2/b=0.75），`@Component` |
| `ai-service/.../rag/RecallFusion.java` | 新增。召回层融合：`RRF`（K=60 倒数排名融合）/ `UNION`（旧语义：向量通道优先、BM25 补位） |
| `ai-service/.../rag/KnowledgeBase.java` | 修改。构造器追加 `InvertedIndex` + `@Value("${ai.rag.fusion-mode:RRF}")`；写入/回滚/删除同步索引；`bm25Search` 从 scrollAll 全量扫描改为倒排查询；`hybridSearch` 走 `RecallFusion` |
| `ai-service/.../resources/application.yml` | 修改。新增 `ai.rag.fusion-mode: RRF` |
| 测试 | 新增 `InvertedIndexTest`（6）、`RecallFusionTest`（4）；`KnowledgeBaseTest`（6，含 3 个新行为测试） |

开发过程中修掉的坑（改这些文件时别回退）：
- `Map.copyOf` 拒绝 null 值（segmentTitle 可为 null）→ InvertedIndex 内做跳 null 的只读快照。
- 回滚顺序：`storedIds.add` 必须在 `indexKeywordChannel` **之前**，否则索引失败的分段漏记、Qdrant 残留孤儿点（有回归测试钉住）。
- `loadFrom` 是**幂等 upsert**（按 id 覆盖、不 clear）——清空重建会把装载前经 API 写入的条目抹掉，有测试钉住。
- `ensureBm25IndexLoaded` 先置位再 `scrollAll`：Qdrant 不可用时返回空列表，不产生每查询重试风暴。

## 待办

### 1. 验证 @Component 修复 + 全量测试
`InvertedIndex` 的 `@Component` 已加（移交前最后一步），但**尚未跑过验证**。
- `set -a; source .env; set +a && ./mvnw -q -pl ai-service test`
- 完成标准：ai-service 测试 0 失败。注意 `AllModelsTest`（@SpringBootTest）**必须带 .env**，无 env 时因 `ARK_API_KEY` 占位符解析失败属既有环境依赖，不是代码回归。

### 2. embedding / chat 切换到公司 GLM key
- Key 来源：cc-switch 桌面端配置（`~/Library/Application Support/com.ccswitch.desktop/` 或 `~/.cc-switch/cc-switch.db`，sqlite）。取 GLM/智谱 provider 条目的 key；**不要把 key 值回显到日志或对话**。
- `.env`（本地）：`ARK_API_KEY`、`OPENAI_API_KEY` 均填 GLM key；`ARK_BASE_URL`、`OPENAI_BASE_URL` 填 GLM OpenAI 兼容端点（`https://open.bigmodel.cn/api/paas/v4`，用前核实）。变量名保持 `ARK_*`/`OPENAI_*` 不改，compose 的 ai-service environment 映射即零改动。
- `application.yml`：`spring.ai.models.list` 三条目的 `api-model-name` 改成 GLM 实际模型名（如 `glm-4.6`），`spring.ai.models.default` 同步；embedding 段 `model: doubao-embedding-vision` 改 GLM embedding 模型（如 `embedding-3`）。
- **换 embedding 模型必须删掉 Qdrant collection `dockit-docs` 重建**：`VectorStore.ensureCollection` 只按"进程内首次维度"建表，旧 collection 维度不匹配会在写入时报错（对应"换模型必须重建索引，旧向量不能混用"）。删除方式：`curl -X DELETE http://localhost:6333/collections/dockit-docs`。
- rerank **不动**：继续 DashScope `qwen3-rerank`（`DASHSCOPE_API_KEY` 保留），Reranker 自带本地 fallback。
- `ARK_EMBEDDING_TPS`（token 限速）按 GLM 实际限流档位重估，拿不准先留 1000。
- 完成标准：ai-service 全量测试绿 + 本地起 Qdrant 后 `KnowledgeQueryService` 一条真实问答链路跑通（embedding/对话都走 GLM）。

### 3. 审查 + 提交
- 跑 `/code-review`（范围：本次全部改动 = 上面 4 新 + 3 改 + application.yml），修掉确认的问题。
- 单独成一个 commit（工作区还有 `pom.xml`/CI/web 等**他人改动**，`git add` 只挑本列表文件）：
  `feat(rag): replace scan-based BM25 with inverted index and RRF recall fusion`
- 完成标准：`git status` 中本列表文件已提交，其余保持原样。

### 4. 可选：基准数字 + 文档
- 重跑 `RagBenchmarkTest`（`source .env && ./mvnw -pl ai-service test -Dtest=RagBenchmarkTest -Dbenchmark=true`，用法见 `docs/rag-benchmark.md`）拿 GLM key 下的索引吞吐/检索 p95；与旧报告对比时注意 embedding 厂商已变，只有链路结构可比。
- 可写一个无外部依赖的微基准（合成语料 1 万段，对比"scrollAll 逐条打分"vs 倒排查询的 avg/p95），面试可引用 O(N)→O(posting) 的量级结论。
- 更新 `docs/ARCHITECTURE.md` 机制 4：BM25 通道为内存倒排索引、召回层 RRF 融合（`ai.rag.fusion-mode` 可配）。

## 设计决策（改实现前先读）
- **为什么 RRF 而不是分数加权**：两路分数量纲不同，直接相加会让数值大的一路主导；RRF 只用名次。K=60 是标准值。
- **UNION 模式保留原因**：旧行为的回归锚点；`fusion-mode` 留作逃生门。
- **倒排在内存、Qdrant 仍是事实源**：进程启动后首次 BM25 查询时一次性装载；此后随 KnowledgeBase 写入/删除同步。跨进程删除（另一实例删文档）感知不到，万级分段下单机单写者可接受。
- **BM25 分词**：CJK 连续段切字符 bigram（单字自成一词），拉丁段切小写词。中文无需词典，英文保词内精度。

## 命令速查
```bash
# 单测（无需 env）：./mvnw -q -pl ai-service test -Dtest='KnowledgeBaseTest,InvertedIndexTest,RecallFusionTest'
# 全量（需 env）： set -a; source .env; set +a && ./mvnw -q -pl ai-service test
# 宿主机直跑连 compose 的中间件：MYSQL_HOST=localhost MYSQL_PORT=3307 REDIS_HOST=localhost RABBITMQ_HOST=localhost
```
