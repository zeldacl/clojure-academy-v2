# Wireless Refactor Contracts

> 状态标签：**现行**（重构约束）

This document defines the temporary contracts used during the domain migration.

## 1. Domain Handler Contract
- Domain modules must expose container lifecycle functions compatible with existing dispatcher flow.
- Required behavior: create container, tick, validate, sync extract/apply, close.
- Domain modules should keep side effects local; shared code must stay stateless when possible.

## 2. Sync Payload Contract
- Payload keys are owned by schema field definitions.
- Server sync pipeline: read state -> coerce -> payload map.
- Client apply pipeline: payload map -> coerce -> atom reset.
- Domain-specific fields must avoid key collisions with shared fields.

## 3. Message Catalog Contract
- Message IDs use format: wireless_<domain>_<action>.
- Domain actions are registered centrally at startup.
- Runtime message lookup must be catalog-based; avoid ad hoc string construction.

## 4. Migration Rule
- New code should import from mcmod generic modules for schema/message/metadata.
- AC compatibility wrappers are temporary and should not receive new features.
