# 07. Схема данных

Текущее состояние базы: миграции `V1`–`V18`, PostgreSQL 18. Диаграмма **производна** от миграций,
а не наоборот: источник правды — `server/archgrid-app/src/main/resources/db/migration/` и
Java-миграция `V10`, и при расхождении прав тот каталог.

Служебная таблица `flyway_schema_history` опущена. Столбцы `created_at`/`updated_at` на диаграмме
не показаны — они есть у `workspace`, `model`, `model_commit`, `profile`, `folder`,
`view_layout_node`, `view_layout_link`, `view_hidden_relationship` и ничего не решают.

## Ядро: репозиторий и его история

Всё, что версионируется коммитами. Таблицы `element`, `relationship` — только идентичность;
изменяемое лежит в `*_version` с интервалом `[from_seq, to_seq)`.

```mermaid
erDiagram
    workspace ||--|| model : "ровно один граф (ADR-0008)"
    model ||--o{ model_commit : "линейная история"
    model ||--o| model_commit : "head_commit_id"
    model_commit ||--o{ model_commit : "parent_id"
    model ||--o{ element : ""
    model ||--o{ relationship : ""
    model ||--o{ profile : ""

    element ||--o{ element_version : "[from_seq, to_seq)"
    relationship ||--o{ relationship_version : "[from_seq, to_seq)"
    element_version }o--o| profile : "специализация"
    relationship_version }o--|| element : "source_id"
    relationship_version }o--|| element : "target_id"

    workspace {
        uuid id PK
        text key UK
        text name
        jsonb settings
    }
    model {
        uuid id PK
        uuid workspace_id FK "UNIQUE — один граф на workspace"
        text key
        text name
        text description
        uuid head_commit_id FK
    }
    model_commit {
        uuid id PK
        uuid model_id FK
        bigint seq "монотонный в пределах модели"
        uuid parent_id FK
        text author
        text message
    }
    element {
        uuid id PK "единственная идентичность (ADR-0016)"
        uuid model_id FK
        text name "зеркало открытой версии; уникально в модели по lower(btrim); null после удаления"
        uuid folder_id FK "организация, не версионируется"
    }
    element_version {
        uuid element_id PK
        bigint from_seq PK
        bigint to_seq "null = текущая"
        text concept_type "всегда базовый концепт ArchiMate"
        uuid profile_id FK
        text name
        text documentation
        jsonb properties
    }
    relationship {
        uuid id PK "единственная идентичность (ADR-0016)"
        uuid model_id FK
        text concept_type "зеркало открытой версии; тройка уникальна в модели; null после удаления"
        uuid source_id FK
        uuid target_id FK
    }
    relationship_version {
        uuid relationship_id PK
        bigint from_seq PK
        bigint to_seq "null = текущая"
        text concept_type "один из 11 типов"
        uuid source_id FK
        uuid target_id FK
        text name
        jsonb properties
    }
    profile {
        uuid id PK
        uuid model_id FK
        text name "уникален в модели"
        text concept_type "базовый концепт"
        jsonb attributes
    }
```

## Представления: определение, оформление, организация

Определение представления версионируется вместе с моделью; всё, что ниже линии `view_version` /
`view_element`, — оформление, живущее вне коммитов, и папки, не значащие ничего для метамодели.

```mermaid
erDiagram
    model ||--o{ view : ""
    model ||--o{ folder : ""
    folder ||--o{ folder : "parent_id"
    element }o--o| folder : "folder_id"
    view }o--o| folder : "folder_id"

    view ||--o{ view_version : "имя, viewpoint, фильтры — коммитами"
    view ||--o{ view_element : "состав — коммитами"
    view_element }o--|| element : ""

    view ||--o{ view_layout_node : "размещения — вне коммитов"
    view_layout_node }o--|| element : "что нарисовано"
    view_layout_node }o--o| view_layout_node : "parent_node_id — вложенность боксов"
    view ||--o{ view_layout_link : "изломы и концы линий — вне коммитов"
    view_layout_link }o--o| relationship : "null у выводимой линии"
    view_layout_link }o--o| view_layout_node : "source/target_node_id"
    view ||--o{ view_hidden_relationship : "линии, которых это представление не рисует"
    view_hidden_relationship }o--|| relationship : ""

    view {
        uuid id PK "единственная идентичность (ADR-0016)"
        uuid model_id FK
        text name "зеркало открытой версии; уникально в модели по lower(btrim); null после удаления"
        uuid folder_id FK
        text router "straight | orthogonal, вне коммитов"
    }
    view_version {
        uuid view_id PK
        bigint from_seq PK
        bigint to_seq "null = текущая"
        text name
        text viewpoint "код из метамодели, не FK"
        jsonb filters "условия на то, что рисуется"
    }
    view_element {
        uuid view_id PK
        uuid element_id PK
        bigint from_seq PK
        bigint to_seq "null = сейчас на представлении"
    }
    view_layout_node {
        uuid id PK "размещение: одно рисование элемента (ADR-0014)"
        uuid view_id FK
        uuid element_id FK "элемент может быть нарисован несколько раз"
        double x
        double y
        double width
        double height
        uuid parent_node_id FK "внутри какого бокса нарисован"
        integer z "порядок наложения"
    }
    view_layout_link {
        uuid id PK "одна линия; связь может быть нарисована несколькими (V15)"
        uuid view_id FK
        text link_key "id связи или derived:<id>:<тип>:<id> выводимой линии, с V15 не уникален"
        uuid relationship_id FK "null, если линия выводимая"
        uuid source_node_id FK "к какому боксу конец; null = где элемент и нарисован"
        uuid target_node_id FK
        jsonb vertices "точки, через которые ведёт линия"
    }
    view_hidden_relationship {
        uuid view_id PK
        uuid relationship_id PK
    }
    folder {
        uuid id PK
        uuid model_id FK
        uuid parent_id FK
        text kind "elements | views"
        text name
    }
    element {
        uuid id PK
        text name "только для индекса уникальности"
    }
    relationship {
        uuid id PK
        text concept_type "с source_id и target_id — только для индекса уникальности"
    }
    model {
        uuid id PK
        text key
    }
```

## Три группы таблиц, и граница между ними — главное в этой схеме

**Арендатор и репозиторий.** `workspace` → `model`, и на `model` висит `unique (workspace_id)`:
workspace держит **ровно один связный граф** ([ADR-0008](adr/0008-one-graph-per-workspace.md)).
Это не экономия таблицы, а условие того, что «где используется», анализ влияния и отчёт о дрейфе
отвечают за весь ландшафт, а не изнутри одного из нескольких моделей — и молча врут про остальные.

**Версионируемое коммитами.** `element`, `relationship`, `view` — таблицы идентичности: `id`,
`model_id` и зеркало того единственного, на чём стоит индекс уникальности — `name` у элемента и
представления, тройка `concept_type / source_id / target_id` у связи ([ADR-0016](adr/0016-one-identity-per-object.md)).
Зеркало обнуляется при удалении: строка идентичности переживает объект ради истории, а имя на ней
блокировало бы следующего владельца. Всё остальное, что меняется, живёт в `*_version` с интервалом
`[from_seq, to_seq)` по последовательности коммитов модели:

```
состояние HEAD       ->  where to_seq is null
состояние коммита N  ->  where from_seq <= N and (to_seq is null or to_seq > N)
```

Оба запроса — один индексный проход, без рекурсии и без второй таблицы, которую пришлось бы
держать согласованной в той же транзакции. Материализованные таблицы «текущего состояния» из
наброска в [02-architecture.md](02-architecture.md) §4.2 отменены именно поэтому. Работает это,
пока история линейна; ветвление потребует обхода предков, к которому схема готова
(`model_commit.parent_id`).

`seq` выдаётся под блокировкой строки в `model` — это же и сериализует конкурентные коммиты.
Оптимистичная проверка `baseCommitId` имеет смысл только если два писателя не могут вклиниться
между проверкой и записью ([ADR-0004](adr/0004-commands-and-versioning-from-day-one.md)).

**Не версионируемое — оформление.** `view_layout_node` — **размещения**: у бокса собственная
идентичность, и один элемент может быть нарисован на представлении несколько раз
([ADR-0014](adr/0014-placements.md)); вложенность — между боксами (`parent_node_id`), не между
элементами. `view_layout_link` — изломы и концы линий (конец указывает на бокс; null — «где элемент
и нарисован»). `view_hidden_relationship`, `view.router`. Перетаскивание прямоугольника не
порождает коммит, иначе история модели становится нечитаемой
([ADR-0003](adr/0003-model-view-layout-separation.md)). Последняя запись побеждает; цена проигранной
гонки — движение мышью, а не информация.

**Организация.** `folder` — дерево на модель, с `kind` (`elements` или `views`). Папка **ничего не
значит для метамодели**: не влияет ни на валидацию, ни на деривацию, ни на глубину C4, и не
версионируется ([ADR-0009](adr/0009-views-and-folders.md)). Удаление папки поднимает содержимое
наверх, а не удаляет его.

## Правила, которые несут вес

**Одна идентичность.** У элемента, связи и представления ровно один идентификатор — UUID, он же
первичный ключ, он же то, чем адресуют команды и API ([ADR-0016](adr/0016-one-identity-per-object.md)).
Колонки `"key"` со slug'ом удалены в V18: их никто не читал, а синтез из имени был источником
дефектов. Человеческий адрес — имя, и за ним стоят правила: имя элемента уникально в модели, имя
представления тоже, связь уникальна по тройке (тип, источник, цель). Правила проверяет
`ModelChange` с объяснением; частичные уникальные индексы V18 по `lower(btrim(name))` и по тройке —
последний рубеж, и их отказ отдаётся как 409. Перестановка двух имён одним батчем проходит, потому
что репозиторий отдаёт имена до того, как берёт. `Slug` остался у `workspace.key` и у ключей
атрибутов профиля.

**Связи представления не хранятся.** Нет таблицы «связи на представлении»: представление рисует те
связи, у которых оба конца на нём. Это то, чего архитектор и ждёт, бросив два элемента рядом, и это
убирает вопрос, что делать со строкой, когда один из концов ушёл. Выводимые связи не хранятся тем
более — они вычисляются при запросе ([ADR-0009](adr/0009-views-and-folders.md)).

**Вложенность живёт в двух местах, и это не дублирование.** «Что чем содержится» — обычная связь
Composition или Aggregation в `relationship_version`. «Что внутри чего нарисовано на этом
представлении» — `view_layout_node.parent_element_id`. Первое — утверждение о системе, второе —
приём представления ([ADR-0011](adr/0011-nesting-is-presentation.md)). Поля `element.parent`
больше нет: миграция `V10` превратила его в строки Composition.

**Правила удаления сказаны явно.** Почти везде `on delete cascade` — удаление workspace уносит
модель целиком. Два исключения: `folder_id` у элемента и представления — `set null` (удалили папку,
содержимое осталось), а `element_version.profile_id` — `restrict`: профиль нельзя удалить, пока по
нему типизирован хоть один элемент, включая исторические версии.

**Ссылки layout'а привязаны к своему представлению составными ключами** (V15): `parent_node_id`
и концы линии обязаны указывать на размещение того же `view_id` — `on delete set null (колонка)`
обнуляет только названную колонку. Находки аудита после V14 закрыты; сам аудит — в
[ADR-0014](adr/0014-placements.md).

**Маршрут линии ключуется линией, а не связью.** `view_layout_link.link_key` — это id связи
*или* вычисляемый ключ выводимой линии (`derived:<id источника>:<тип>:<id цели>`, из
идентификаторов, а не имён — ограничен и не меняется при переименовании), а `relationship_id` заполнен
только в первом случае и служит для каскадной уборки. Пока ключом был внешний ключ на
`relationship`, изломы выводимых линий редактор принимал, отправлял на сервер и терял: вставке было
не с чем сопоставить строку (миграция V13).

**Один узел на элемент в пределах вида — решение, а не недосмотр.** `view_layout_node` держит
`primary key (view_id, element_id)`, тогда как обменный формат ArchiMate разрешает нарисовать один
элемент на виде дважды. Поддержать это значит завести идентичность диаграммного объекта и протащить
её через весь редактор, где сейчас ключ элемента и есть идентификатор фигуры. Цена решения —
импорт схлопывает дубли и говорит об этом ([ADR-0012](adr/0012-archimate-exchange-format.md)).

**Чего в схеме нет специально.** Столбца «тип C4» — уровень C4 вычисляется как глубина
относительно фокуса представления ([ADR-0007](adr/0007-c4-projection.md)). Таблицы метамодели —
это версионируемый файл, а не данные арендатора ([ADR-0002](adr/0002-metamodel-as-data.md)); поэтому
`view_version.viewpoint` и `*.concept_type` — текст, проверяемый приложением, а не внешние ключи.
