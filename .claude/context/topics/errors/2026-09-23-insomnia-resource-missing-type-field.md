---
name: insomnia-resource-missing-type-field
description: Hand-added requests in tools/insomnia/challenge-collection.json were silently dropped on import because they lacked the _type field
metadata:
  type: error
---

# Gotcha: Insomnia silently drops a resource missing `_type`

Added 7 new `GET /notification_events` filter-case requests to `tools/insomnia/challenge-collection.json` by hand (Python script constructing the JSON). Valid JSON, correct `parentId`, all other fields matched a known-good request - but Insomnia's importer requires `_type: "request"` on every resource, and its absence causes a **silent** drop on import (no error, no partial-import warning). Confirmed by diffing key sets against a working request.

**When hand-editing this file**: always diff the new resource's key set against an existing one of the same kind before considering it done; `python3 -c "print(sorted(good.keys()) == sorted(new.keys()))"` catches this class of mistake immediately.
