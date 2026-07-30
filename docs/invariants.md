# Invariant language

WorkflowGuard evaluates invariants against the JSON body returned by the same `PROBE` step before the mutation, after the mutation, and—when cleanup exists—after cleanup.

## Built-in rules

- `UNCHANGED`: the selected subtree must remain semantically unchanged. Workflow-level volatile JSON Pointers are ignored.
- `ABSENT`: the selected value must be missing after execution.
- `EQUALS`: the selected value must equal the supplied JSON literal.
- `ARRAY_SIZE_UNCHANGED`: both selected values must be arrays with the same size.
- `MUST_NOT_CONTAIN`: an array must not contain the supplied JSON value; for a scalar, the scalar must differ.
- `EXPRESSION`: the restricted expression must evaluate to `true`.

## Expression syntax

References use `before:/json/pointer` and `after:/json/pointer`. An empty pointer addresses the root. Literals use JSON syntax: strings require double quotes, while numbers, booleans, and `null` do not.

Supported operators:

```text
!  &&  ||
==  !=  >  >=  <  <=
(  )
```

Ordered comparisons accept two numbers or two strings. Equality compares JSON values semantically.

Supported functions:

```text
exists(after:/id)
changed(/status)
unchanged(/owner)
```

Examples:

```text
after:/count == before:/count
after:/balance >= 0 && unchanged(/owner)
exists(after:/id) && after:/status != "deleted"
!changed(/permissions)
```

The evaluator has no general function calls, reflection, filesystem access, network access, or JavaScript engine. Invalid syntax fails the invariant check with a position-aware evaluation error.

## Volatile paths

Use **Invariants → Volatile JSON paths** to enter one JSON Pointer per line, such as:

```text
/requestId
/metadata/generatedAt
```

The paths and their descendants are ignored by `UNCHANGED`. They do not change the meaning of explicit equality or expression rules.
