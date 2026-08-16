# ArchGrid — документация

Architecture Knowledge Platform: архитектурный граф как source of truth, диаграммы — его представления.

## Принятые вводные

| Параметр | Значение |
|---|---|
| Рынок | Enterprise on-prem / private cloud |
| MVP | Редактор + типизированный граф |
| Команда | Один разработчик, full-time |
| Backend | Java 21 + Spring Boot 4.1; Python — только AI-сайдкар |
| LLM | Плагинуемый провайдер (внешний API / self-hosted) |
| Метамодель | ArchiMate 3.2-совместимая с самого начала |
| Источники данных | Импорт из артефактов (OpenAPI, K8s, Terraform) и из инструментов (ArchiMate OEF, Structurizr, PlantUML, draw.io) |

## Документы

- [Исходная идея](../idea_summary.md)
- [01 — Критический разбор техстека](01-tech-stack-review.md) — что из идеи принято, что отклонено и почему
- [02 — Целевая архитектура](02-architecture.md) — слои, модель данных, поток изменений, требования on-prem
- [03 — План проекта](03-roadmap.md) — 9 фаз, оценки, вехи, риски

## Решения (ADR)

| № | Решение | Статус |
|---|---|---|
| [0001](adr/0001-postgres-instead-of-neo4j.md) | PostgreSQL как единственное хранилище вместо Neo4j | accepted |
| [0002](adr/0002-metamodel-as-data.md) | Метамодель ArchiMate как данные, а не как код | accepted |
| [0003](adr/0003-model-view-layout-separation.md) | Трёхслойное разделение: модель / представление / layout | accepted |
| [0004](adr/0004-commands-and-versioning-from-day-one.md) | Изменения — только команды; версии в схеме с первого дня | accepted |
| [0005](adr/0005-collaboration-strategy.md) | Presence сейчас, CRDT только для layout потом | accepted |
| [0006](adr/0006-ai-as-isolated-optional-sidecar.md) | AI — изолированный отключаемый sidecar | accepted |

## Ключевые вехи

| Веха | Срок | Содержание |
|---|---|---|
| M1 | ~16 недель | Демонстрируемый MVP: типизированный граф, валидация, C4 + Deployment представления |
| M2 | ~21 неделя | История, ADR, DSL, arc42 — можно отдавать design partners |
| M3 | ~28 недель | Импорт и drift report — граф перестаёт устаревать |
| M4 | ~33 недели | AI Copilot |
| M5 | ~38 недель | Продаваемая on-prem v1 |
