"""Margin: paragraph compare-and-set with durable conflict receipts."""

from base import BaseService, Problem, fields, integer, text, run


class Service(BaseService):
    KIND = "margin"

    def seed(self):
        return {
            "paragraphs": [
                {"id": key, "text": value, "revision": 1, "deleted": False}
                for key, value in [
                    ("p-arrival", "Meet at the north studio at 09:00."),
                    ("p-supplies", "Bring pencils and a notebook."),
                    ("p-access", "The side entrance has a ramp."),
                ]
            ]
        }

    def view(self, state, now):
        return state

    def apply(self, state, action, args, now):
        if action not in {"save", "remote", "delete"}:
            raise Problem("Unknown action")
        fields(args, {"paragraphId", "baseRevision"} | ({"text"} if action != "delete" else set()))
        key = text(args["paragraphId"], 40)
        base = integer(args["baseRevision"], 1)
        value = text(args["text"], 2000) if action != "delete" else None
        row = next((p for p in state["paragraphs"] if p["id"] == key), None)
        if not row or row["deleted"]:
            return {"accepted": False, "reason": "Paragraph is deleted or unknown"}
        if row["revision"] != base:
            return {
                "accepted": False,
                "reason": "Paragraph changed; compare your draft with the current paragraph",
                "conflict": dict(row),
            }
        row["revision"] += 1
        if action == "delete":
            row["deleted"] = True
        else:
            row["text"] = value
        return {"accepted": True, "paragraphId": key, "revision": row["revision"]}


if __name__ == "__main__":
    run(Service, 8919)
