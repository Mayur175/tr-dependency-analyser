# gCTS Task Dependency Analyzer

Eclipse ADT plugin that detects cross-task object dependencies in SAP gCTS Transport Requests and recommends a safe pull order to prevent activation failures.

## Install via Eclipse Update Site

```
Help → Install New Software → Add
URL: https://pages.github.tools.sap/I763161/gcts-analyzer/updatesite
```

## Features

- One right-click on any TR → instant dependency analysis
- Detects CRITICAL (same-object conflict), HIGH (activation dependency), MEDIUM (type reference) risks
- Recommends exact pull order — which tasks must be pulled together
- Supported: CLAS, INTF, TABL, DTEL, DDLS, DDLX, BDEF, FUGR
- Dedicated Eclipse View with cluster tree + Zest visual graph
- CSV export for audit trail
- ATC check integration
- Analysis history persisted to ABAP database table

## Requirements

- Eclipse IDE for RCP and RAP Developers
- SAP ABAP Development Tools (ADT)
- SAP S/4HANA Cloud or BTP ABAP Environment
- ABAP backend deployed (see `TR dependency/abap/`)

## Build

```bash
cd eclipse
mvn clean package -DskipTests
```

Output: `com.gmw.gcts.analyzer.updatesite/target/repository/`
