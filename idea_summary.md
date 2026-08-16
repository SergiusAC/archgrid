### Кратко

Идея — сделать **Architecture Knowledge Platform**, а не просто аналог draw.io.

**Ключевой принцип:**

> Архитектурный граф — source of truth, диаграммы — его визуальные представления.

### Архитектура

* **Frontend:** React + TypeScript + React Flow — веб-редактор диаграмм.
* **Architecture Model:** метамодель объектов и допустимых связей: Systems, Services, APIs, DB, Kafka, Business Objects и т.д.
* **Graph DB:** Neo4j — хранение архитектурных объектов и зависимостей.
* **Diagram Storage:** отдельно хранить semantic model и visual layout.
* **Backend:** FastAPI или Spring Boot.
* **Search:** OpenSearch + vector search.
* **Collaboration:** WebSocket + Yjs/CRDT.
* **DSL:** Architecture-as-Code для Git/PR/review.

### Главная фича редактора

Пользователь рисует:

`Payment Service → Kafka → Notification Service`

Система автоматически понимает, что это архитектурные объекты и связи, а не просто прямоугольники и стрелки.

Поэтому один и тот же граф можно показать как:

* C4 Context View
* UML Sequence
* ArchiMate View
* Deployment View
* Security View

### AI

LLM становится **Architecture Copilot**:

* Text → Diagram
* генерация PlantUML/Mermaid/ArchiMate
* AI architecture review
* поиск архитектурных проблем
* suggestions при рисовании
* генерация Arc42/ADR
* semantic search
* impact analysis

### Самая сильная концепция

**Architecture Graph + Diagram Editor + Documentation + Jira/Confluence + AI**

В итоге получается примерно:

> **Git + Figma + Knowledge Graph + AI Copilot для Enterprise Architecture.**

Именно это отличает продукт от обычного diagramming tool.
