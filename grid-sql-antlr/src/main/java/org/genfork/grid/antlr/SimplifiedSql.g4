grammar SimplifiedSql;

options {
	caseInsensitive = true;
}

// Entry points: QueryParser uses `query`; SqlEngine uses `statement`;
// JDBC tooling multi-statement scripts use `script`.

statement
    : executable SEMI* EOF
    ;

script
    : executable (SEMI+ executable)* SEMI* EOF
    ;

executable
    : withQuery
    | query
    | insertStmt
    | mergeStmt
    | deleteStmt
    | updateStmt
    | analyzeStmt
    | createTableStmt
    | dropTableStmt
    | createIndexStmt
    | dropIndexStmt
    | createSchemaStmt
    | dropSchemaStmt
    | setSchemaStmt
    | setRemoteDirtyStmt
    | alterTableStmt
    | createViewStmt
    | dropViewStmt
    | createMaterializedViewStmt
    | refreshMaterializedViewStmt
    | createFunctionStmt
    | dropFunctionStmt
    | createTriggerStmt
    | dropTriggerStmt
    | createSequenceStmt
    | dropSequenceStmt
    | selectSequenceStmt
    | explainStmt
    | beginStmt
    | commitStmt
    | rollbackStmt
    | savepointStmt
    | rollbackToSavepointStmt
    | releaseSavepointStmt
    | prepareStmt
    | executeStmt
    | deallocateStmt
    | pinStmt
    | unpinStmt
    | createUserStmt
    | dropUserStmt
    | alterUserStmt
    | createRoleStmt
    | dropRoleStmt
    | grantStmt
    | revokeStmt
    ;

createUserStmt: CREATE USER ID PASSWORD STRING;
dropUserStmt: DROP USER ID;
alterUserStmt: ALTER USER ID PASSWORD STRING;
createRoleStmt: CREATE ROLE ID;
dropRoleStmt: DROP ROLE ID;
/** Role membership, privilege-to-role, or privilege-to-user (order matters). */
grantStmt
    : GRANT ROLE ID TO ID
    | GRANT privilegeList ON privilegeTarget TO ROLE ID
    | GRANT privilegeList ON privilegeTarget TO ID
    ;
revokeStmt: REVOKE privilegeList ON privilegeTarget FROM ID;
privilegeList: privilegeName (',' privilegeName)*;
privilegeName: SELECT | INSERT | UPDATE | DELETE | DDL;
privilegeTarget: SCHEMA ID | TABLE tableName;

/** Soft pin / QoS overlay (not a second row store): PIN KEY t k [TTL ms] [QOS tag]. */
pinStmt
    : PIN KEY tableName value (TTL INT)? (QOS (STRING | ID))?
    ;

unpinStmt
    : UNPIN KEY tableName value
    ;

beginStmt
    : BEGIN (TRANSACTION | WORK)?
    | START TRANSACTION
    ;

commitStmt
    : COMMIT (TRANSACTION | WORK)?
    ;

rollbackStmt
    : ROLLBACK (TRANSACTION | WORK)?
    ;

savepointStmt
    : SAVEPOINT ID
    ;

rollbackToSavepointStmt
    : ROLLBACK TO (SAVEPOINT)? ID
    ;

releaseSavepointStmt
    : RELEASE SAVEPOINT ID
    ;

prepareStmt
    : PREPARE ID AS executable
    ;

executeStmt
    : EXECUTE ID (USING value (',' value)*)?
    ;

deallocateStmt
    : DEALLOCATE (PREPARE)? ID
    ;

withQuery
    : WITH RECURSIVE? cteDef (',' cteDef)* query
    ;

cteDef
    : ID AS '(' query ')'
    ;

createViewStmt
    : CREATE VIEW tableName AS query
    ;

/** SPI bind: class + method (no string eval / scripting). */
createFunctionStmt
    : CREATE FUNCTION ID '(' (funcParam (',' funcParam)*)? ')'
      (RETURNS typeName | RETURNS TABLE ('(' tableFuncCol (',' tableFuncCol)* ')')?)
      AS CLASS STRING METHOD STRING
    ;

funcParam
    : ID typeName
    ;

tableFuncCol
    : ID typeName
    ;

dropFunctionStmt
    : DROP FUNCTION (IF EXISTS)? ID
    ;

/**
 * Trigger: FOR EACH ROW|STATEMENT; body/WHEN strings re-parsed with OLD/NEW as value refs.
 */
createTriggerStmt
    : CREATE TRIGGER ID (BEFORE | AFTER) (INSERT | UPDATE | DELETE)
      ON tableName FOR EACH (ROW | STATEMENT) (WHEN STRING)? AS STRING
    ;

dropTriggerStmt
    : DROP TRIGGER (IF EXISTS)? ID (ON tableName)?
    ;

dropViewStmt
    : DROP VIEW (IF EXISTS)? tableName
    ;

createMaterializedViewStmt
    : CREATE MATERIALIZED VIEW tableName AS query
    ;

refreshMaterializedViewStmt
    : REFRESH MATERIALIZED VIEW tableName
    ;

/** Top-level SELECT or {UNION|INTERSECT|EXCEPT} [ALL] chain of selects. */
query
    : selectQuery (unionTail)*
    | selectExprQuery
    ;

unionTail
    : setOperator ALL? selectQuery
    ;

setOperator
    : UNION
    | INTERSECT
    | EXCEPT
    ;

selectQuery
    : SELECT (DISTINCT)? selectList FROM fromItem (joinClause)* (WHERE expression)? (GROUP BY groupByList)? (HAVING havingExpr=expression)? windowClause? (ORDER BY orderList)? (LIMIT limitClause)? (OFFSET offsetInt=INT)? forUpdateClause?
    ;

/**
 * Bare SELECT of expressions (no FROM) — e.g. {@code SELECT fn(1, 2)} mutating UDF calls.
 * Prefer {@link #selectQuery} when FROM is present (ordered first in {@code query}).
 */
selectExprQuery
    : SELECT selectItem (',' selectItem)*
    ;

/** One or more GROUP BY columns (composite wire key in executor). */
groupByList
    : columnName (',' columnName)*
    ;

forUpdateClause
    : FOR UPDATE (SKIP_KW LOCKED)?
    ;

windowClause
    : WINDOW windowDef (',' windowDef)*
    ;

windowDef
    : ID AS '(' windowSpec ')'
    ;

windowSpec
    : (PARTITION BY partitionByList)? (ORDER BY orderList)?
    ;

/** One or more PARTITION BY columns (composite wire key in window ops). */
partitionByList
    : columnName (',' columnName)*
    ;

fromItem
    : tableName
    | functionCall (AS alias=ID)?
    ;

explainStmt
    : EXPLAIN ANALYZE? explainBody
    ;

explainBody
    : withQuery
    | query
    | insertStmt
    | mergeStmt
    | deleteStmt
    | updateStmt
    | analyzeStmt
    | createTableStmt
    | dropTableStmt
    | createIndexStmt
    | dropIndexStmt
    | createSchemaStmt
    | dropSchemaStmt
    | setSchemaStmt
    | setRemoteDirtyStmt
    | alterTableStmt
    | createViewStmt
    | dropViewStmt
    | createMaterializedViewStmt
    | refreshMaterializedViewStmt
    | createFunctionStmt
    | dropFunctionStmt
    | createTriggerStmt
    | dropTriggerStmt
    | createSequenceStmt
    | dropSequenceStmt
    | selectSequenceStmt
    | beginStmt
    | commitStmt
    | rollbackStmt
    | savepointStmt
    | rollbackToSavepointStmt
    | releaseSavepointStmt
    | prepareStmt
    | executeStmt
    | deallocateStmt
    | pinStmt
    | unpinStmt
    | createUserStmt
    | dropUserStmt
    | createRoleStmt
    | dropRoleStmt
    | grantStmt
    | revokeStmt
    ;

/** Crude table stats for planner (cardinality / fan-out hints). */
analyzeStmt
    : ANALYZE tableName
    ;

/**
 * INSERT (reject duplicate PK) or UPSERT (alias of ON CONFLICT DO UPDATE from VALUES /
 * OpLog UPSERT overwrite).
 */
insertStmt
    : (INSERT | UPSERT) INTO tableName ('(' insertColumnList ')')? VALUES valueTuple (',' valueTuple)*
      onConflictClause? returningClause?
    ;

returningClause
    : RETURNING columnList
    ;

onConflictClause
    : ON CONFLICT ('(' columnName (',' columnName)* ')')? conflictAction
    ;

conflictAction
    : DO NOTHING
    | DO UPDATE SET updateAssign (',' updateAssign)*
    ;

/**
 * Single-row MERGE: USING (VALUES тАж) or USING table; PK/unique ON equality;
 * WHEN MATCHED UPDATE + WHEN NOT MATCHED INSERT.
 */
mergeStmt
    : MERGE INTO tableName
      USING mergeSource
      ON columnName '=' columnName
      whenMatchedClause?
      whenNotMatchedClause?
    ;

mergeSource
    : '(' VALUES valueTuple ')'
    | tableName
    ;

whenMatchedClause
    : WHEN MATCHED THEN UPDATE SET updateAssign (',' updateAssign)*
    ;

whenNotMatchedClause
    : WHEN NOT MATCHED THEN INSERT ('(' insertColumnList ')')? VALUES valueTuple
    ;

insertColumnList
    : columnName (',' columnName)*
    ;

valueTuple
    : '(' value (',' value)* ')'
    ;

deleteStmt
    : DELETE FROM tableName WHERE expression
    ;

updateStmt
    : UPDATE targetTable=tableName SET updateAssign (',' updateAssign)*
      (FROM sourceTable=tableName)?
      WHERE expression returningClause?
    ;

updateAssign
    : columnName '=' updateRhs
    | columnName '=' value
    ;

updateRhs
    : columnName (CONCAT_OP value)+
    | columnName '+' value
    | CONCAT '(' columnName ',' value ')'
    ;

createTableStmt
    : CREATE TABLE (IF NOT EXISTS)? tableName '(' tableElement (',' tableElement)* ')'
    ;

tableElement
    : columnDef
    | PRIMARY KEY '(' columnName (',' columnName)* ')'
    | (CONSTRAINT constraintName)? FOREIGN KEY '(' columnName (',' columnName)* ')'
      REFERENCES tableName '(' columnName (',' columnName)* ')'
      (ON DELETE referentialAction)? (ON UPDATE referentialAction)?
    | (CONSTRAINT constraintName)? CHECK '(' expression ')'
    ;

columnDef
    : columnName serialType (PRIMARY KEY)?
    | columnName typeName (NOT NULL)? identityClause? (PRIMARY KEY)?
    | columnName typeName (NOT NULL)? (PRIMARY KEY)? identityClause?
    ;

identityClause
    : GENERATED BY DEFAULT AS IDENTITY
    ;

serialType
    : SERIAL
    | BIGSERIAL
    ;

constraintName
    : ID
    ;

referentialAction
    : RESTRICT
    | CASCADE
    | SET NULL
    ;

typeName
    : UUID_TYPE
    | DATE_TYPE
    | TIME_TYPE
    | TIMESTAMP_TYPE
    | TIMESTAMPTZ_TYPE
    | ID
    ;

createSequenceStmt
    : CREATE SEQUENCE (IF NOT EXISTS)? sequenceName
      (START WITH INT)? (INCREMENT BY INT)? RECLAIM?
    ;

dropSequenceStmt
    : DROP SEQUENCE (IF EXISTS)? sequenceName
    ;

selectSequenceStmt
    : SELECT sequenceCall
    ;

sequenceCall
    : NEXTVAL '(' sequenceNameArg ')'
    | CURRVAL '(' sequenceNameArg ')'
    ;

sequenceName
    : ID ('.' ID)?
    ;

sequenceNameArg
    : STRING
    | ID
    ;

dropTableStmt
    : DROP TABLE (IF EXISTS)? tableName
    ;

createIndexStmt
    : CREATE (UNIQUE | BITMAP)? INDEX indexName ON tableName '(' columnName (',' columnName)* ')'
    ;

dropIndexStmt
    : DROP INDEX indexName (ON tableName)?
    ;

indexName
    : ID
    ;

joinClause
    : (LEFT (OUTER)? | RIGHT (OUTER)? | FULL (OUTER)? | INNER)? JOIN tableName ON columnName '=' columnName
    ;

selectList
    : '*'
    | selectItem (',' selectItem)*
    ;

selectItem
    : columnName (AS alias=ID)?
    | aggregateExpr
    | windowExpr
    | functionCall (AS alias=ID)?
    ;

functionCall
    : ID '(' (funcArg (',' funcArg)*)? ')'
    ;

funcArg
    : columnName
    | value
    ;

aggregateExpr
    : COUNT '(' '*' ')'
    | SUM '(' columnName ')'
    | AVG '(' columnName ')'
    | MIN '(' columnName ')'
    | MAX '(' columnName ')'
    ;

windowExpr
    : (ROW_NUMBER | RANK | DENSE_RANK) '(' ')' overClause
    | (LAG | LEAD) '(' columnName ')' overClause
    | (SUM | MIN | MAX | AVG) '(' columnName ')' overClause
    ;

overClause
    : OVER ('(' windowSpec ')' | windowName=ID)
    ;

columnList
    : '*' | columnName (',' columnName)*
    ;

columnName
    : ID ('.' ID)?
    ;

tableName
    : ID ('.' ID)?
    ;

createSchemaStmt
    : CREATE SCHEMA (IF NOT EXISTS)? ID (AUTHORIZATION ID)?
    ;

dropSchemaStmt
    : DROP SCHEMA (IF EXISTS)? ID (RESTRICT | CASCADE)?
    ;

setSchemaStmt
    : SET SCHEMA ID
    ;

setRemoteDirtyStmt
    : SET REMOTE_DIRTY trueFalseExpression
    ;

alterTableStmt
    : ALTER TABLE tableName ADD COLUMN columnDef
    | ALTER TABLE tableName ADD (CONSTRAINT constraintName)? CHECK '(' expression ')'
    | ALTER TABLE tableName DROP COLUMN columnName
    ;

expression
    : expression AND expression          # AndExpression
    | expression OR expression           # OrExpression
    | NOT expression                     # NotExpression
    | predicate                          # PredicateExpression
    | '(' expression ')'                 # ParenExpression
    | trueFalseExpression				 # TrueOrFalseExpression
    ;

predicate
    : columnName operator value                    # Comparison
    | columnName operator columnName               # ColumnComparison
    | columnName operator '(' selectQuery ')'      # ComparisonSubquery
    | functionCall operator value                  # FunctionComparison
    | aggregateExpr operator value                 # AggComparison
    | columnName BETWEEN value AND value           # Between
    | columnName IN '(' valueList ')'              # In
    | columnName IN '(' selectQuery ')'            # InSubquery
    | EXISTS '(' selectQuery ')'                   # ExistsSubquery
    | columnName LIKE STRING                       # Like
    | columnName IS NULL                           # IsNull
    | columnName IS NOT NULL                       # IsNotNull
    ;

trueFalseExpression
	: TRUE
	| FALSE
	;

valueList
    : value (',' value)*
    ;

operator
    : '=' | '!=' | '>' | '>=' | '<' | '<='
    ;

value
    : INT
    | FLOAT
    | STRING
    | NULL
    | TRUE
    | FALSE
    | PARAM
    | oldNewRef
    | CAST '(' value AS typeName ')'
    | UUID_TYPE STRING
    | DATE_TYPE STRING
    | TIME_TYPE STRING
    | TIMESTAMP_TYPE STRING
    | TIMESTAMPTZ_TYPE STRING
    | caseExpr
    | sequenceCall
    ;

/** Trigger body/WHEN only: OLD.col / NEW.col (parsed via parseTriggerBody). */
oldNewRef
    : (OLD | NEW) '.' ID
    ;

caseExpr
    : CASE (WHEN expression THEN value)+ (ELSE value)? END
    ;

orderList
    : orderItem (',' orderItem)*
    ;

orderItem
    : columnName (ASC | DESC)?
    ;

/**
 * {@code LIMIT n} or MySQL-style {@code LIMIT offset, count}.
 * Prefer {@code LIMIT n OFFSET m} via the separate {@code OFFSET} clause on selectQuery.
 */
limitClause
    : INT (',' INT)?
    ;

SELECT: 'SELECT';
EXPLAIN: 'EXPLAIN';
ANALYZE: 'ANALYZE';
INSERT: 'INSERT';
UPSERT: 'UPSERT';
INTO: 'INTO';
VALUES: 'VALUES';
DELETE: 'DELETE';
UPDATE: 'UPDATE';
SET: 'SET';
REMOTE_DIRTY: 'REMOTE_DIRTY';
MERGE: 'MERGE';
CONFLICT: 'CONFLICT';
DO: 'DO';
NOTHING: 'NOTHING';
MATCHED: 'MATCHED';
CREATE: 'CREATE';
DROP: 'DROP';
ALTER: 'ALTER';
ADD: 'ADD';
COLUMN: 'COLUMN';
SCHEMA: 'SCHEMA';
TABLE: 'TABLE';
VIEW: 'VIEW';
MATERIALIZED: 'MATERIALIZED';
REFRESH: 'REFRESH';
FUNCTION: 'FUNCTION';
TRIGGER: 'TRIGGER';
RETURNS: 'RETURNS';
CLASS: 'CLASS';
METHOD: 'METHOD';
BEFORE: 'BEFORE';
AFTER: 'AFTER';
EACH: 'EACH';
WITH: 'WITH';
RECURSIVE: 'RECURSIVE';
UNION: 'UNION';
INTERSECT: 'INTERSECT';
EXCEPT: 'EXCEPT';
ALL: 'ALL';
INDEX: 'INDEX';
UNIQUE: 'UNIQUE';
BITMAP: 'BITMAP';
PRIMARY: 'PRIMARY';
KEY: 'KEY';
IF: 'IF';
EXISTS: 'EXISTS';
NOT: 'NOT';
NULL: 'NULL';
FROM: 'FROM';
FOR: 'FOR';
SKIP_KW: 'SKIP';
LOCKED: 'LOCKED';
RETURNING: 'RETURNING';
WHERE: 'WHERE';
GROUP: 'GROUP';
HAVING: 'HAVING';
ORDER: 'ORDER';
BY: 'BY';
LIMIT: 'LIMIT';
OFFSET: 'OFFSET';
DISTINCT: 'DISTINCT';
COUNT: 'COUNT';
SUM: 'SUM';
AVG: 'AVG';
MIN: 'MIN';
MAX: 'MAX';
CONCAT: 'CONCAT';
CAST: 'CAST';
UUID_TYPE: 'UUID';
DATE_TYPE: 'DATE';
TIME_TYPE: 'TIME';
TIMESTAMP_TYPE: 'TIMESTAMP';
TIMESTAMPTZ_TYPE: 'TIMESTAMPTZ';
CASE: 'CASE';
WHEN: 'WHEN';
THEN: 'THEN';
ELSE: 'ELSE';
END: 'END';
OVER: 'OVER';
WINDOW: 'WINDOW';
PARTITION: 'PARTITION';
ROW_NUMBER: 'ROW_NUMBER';
ROW: 'ROW';
RANK: 'RANK';
DENSE_RANK: 'DENSE_RANK';
LAG: 'LAG';
LEAD: 'LEAD';
JOIN: 'JOIN';
INNER: 'INNER';
LEFT: 'LEFT';
RIGHT: 'RIGHT';
FULL: 'FULL';
OUTER: 'OUTER';
ON: 'ON';
RESTRICT: 'RESTRICT';
CASCADE: 'CASCADE';
AUTHORIZATION: 'AUTHORIZATION';
FOREIGN: 'FOREIGN';
REFERENCES: 'REFERENCES';
CONSTRAINT: 'CONSTRAINT';
CHECK: 'CHECK';
SEQUENCE: 'SEQUENCE';
SERIAL: 'SERIAL';
BIGSERIAL: 'BIGSERIAL';
GENERATED: 'GENERATED';
DEFAULT: 'DEFAULT';
IDENTITY: 'IDENTITY';
INCREMENT: 'INCREMENT';
START: 'START';
RECLAIM: 'RECLAIM';
NEXTVAL: 'NEXTVAL';
CURRVAL: 'CURRVAL';
AND: 'AND';
OR: 'OR';
BETWEEN: 'BETWEEN';
IN: 'IN';
LIKE: 'LIKE';
IS: 'IS';
TRUE: 'TRUE';
FALSE: 'FALSE';
ASC: 'ASC';
DESC: 'DESC';
BEGIN: 'BEGIN';
COMMIT: 'COMMIT';
ROLLBACK: 'ROLLBACK';
SAVEPOINT: 'SAVEPOINT';
RELEASE: 'RELEASE';
TRANSACTION: 'TRANSACTION';
WORK: 'WORK';
OLD: 'OLD';
NEW: 'NEW';
STATEMENT: 'STATEMENT';
PREPARE: 'PREPARE';
EXECUTE: 'EXECUTE';
DEALLOCATE: 'DEALLOCATE';
AS: 'AS';
USING: 'USING';
PIN: 'PIN';
UNPIN: 'UNPIN';
TTL: 'TTL';
QOS: 'QOS';
USER: 'USER';
PASSWORD: 'PASSWORD';
ROLE: 'ROLE';
GRANT: 'GRANT';
REVOKE: 'REVOKE';
TO: 'TO';
DDL: 'DDL';
CONCAT_OP: '||';
PARAM: '?';
SEMI: ';';

ID: [a-z_][a-z0-9_]*;
INT: [0-9]+;
FLOAT: [0-9]+ '.' [0-9]* | '.' [0-9]+;
STRING: '\'' ('\'\'' | ~'\'')* '\'';

WS: [ \t\r\n]+ -> skip;
