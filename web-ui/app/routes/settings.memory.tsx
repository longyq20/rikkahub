import * as React from "react";

import { Link } from "react-router";
import { Brain, ChevronLeft, Home, Plus, Save, Trash2 } from "lucide-react";
import { toast } from "sonner";

import { Button } from "~/components/ui/button";
import { useConfirm, usePrompt } from "~/components/confirm-dialog-provider";
import { Input } from "~/components/ui/input";
import { ScrollArea } from "~/components/ui/scroll-area";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "~/components/ui/select";
import { Textarea } from "~/components/ui/textarea";
import api from "~/services/api";
import { useSettingsStore } from "~/stores";

export function meta() {
  return [{ title: "Settings - Memory" }];
}

type MemoryRecord = {
  id: number;
  assistantId: string;
  content: string;
};

type MemoryListResponse = {
  assistantId: string;
  items: MemoryRecord[];
};

export default function SettingsMemoryPage() {
  const settings = useSettingsStore((state) => state.settings);
  const assistants = settings?.assistants ?? [];
  const defaultAssistantId = settings?.assistantId ?? assistants[0]?.id ?? "";

  const [assistantId, setAssistantId] = React.useState(defaultAssistantId);
  const [items, setItems] = React.useState<MemoryRecord[]>([]);
  const [newContent, setNewContent] = React.useState("");
  const [loading, setLoading] = React.useState(false);
  const [busy, setBusy] = React.useState(false);
  const confirm = useConfirm();
  const prompt = usePrompt();

  React.useEffect(() => {
    if (!assistantId && defaultAssistantId) {
      setAssistantId(defaultAssistantId);
    }
  }, [assistantId, defaultAssistantId]);

  const load = React.useCallback(async () => {
    if (!assistantId) {
      setItems([]);
      return;
    }

    setLoading(true);
    try {
      const result = await api.get<MemoryListResponse>(`memory?assistantId=${encodeURIComponent(assistantId)}`);
      setItems(result.items ?? []);
    } catch (error) {
      console.error("load memory failed", error);
      toast.error(error instanceof Error ? error.message : "Load memory failed");
    } finally {
      setLoading(false);
    }
  }, [assistantId]);

  React.useEffect(() => {
    void load();
  }, [load]);

  const createMemory = React.useCallback(async () => {
    const content = newContent.trim();
    if (!assistantId) return;
    if (!content) {
      toast.error("Content cannot be empty");
      return;
    }

    setBusy(true);
    try {
      await api.post<MemoryRecord>("memory", { assistantId, content });
      setNewContent("");
      toast.success("Memory added");
      await load();
    } catch (error) {
      console.error("create memory failed", error);
      toast.error(error instanceof Error ? error.message : "Create failed");
    } finally {
      setBusy(false);
    }
  }, [assistantId, load, newContent]);

  const editMemory = React.useCallback(
    async (item: MemoryRecord) => {
      const next = (
        await prompt({
          title: "Edit memory",
          description: "Update memory content",
          defaultValue: item.content,
          confirmText: "Save",
          cancelText: "Cancel",
        })
      )?.trim();
      if (next == null) return;
      if (!next) {
        toast.error("Content cannot be empty");
        return;
      }
      if (next === item.content) return;

      setBusy(true);
      try {
        await api.put<MemoryRecord>(`memory/${item.id}`, { assistantId, content: next });
        toast.success("Memory updated");
        await load();
      } catch (error) {
        console.error("update memory failed", error);
        toast.error(error instanceof Error ? error.message : "Update failed");
      } finally {
        setBusy(false);
      }
    },
    [assistantId, load, prompt],
  );

  const deleteMemory = React.useCallback(
    async (item: MemoryRecord) => {
      const confirmed = await confirm({
        title: "Delete memory?",
        description: "Delete this memory?",
        confirmText: "Delete",
        cancelText: "Cancel",
        destructive: true,
      });
      if (!confirmed) return;

      setBusy(true);
      try {
        await api.delete<{ status: string }>(
          `memory/${item.id}?assistantId=${encodeURIComponent(assistantId || item.assistantId)}`,
        );
        toast.success("Memory deleted");
        await load();
      } catch (error) {
        console.error("delete memory failed", error);
        toast.error(error instanceof Error ? error.message : "Delete failed");
      } finally {
        setBusy(false);
      }
    },
    [assistantId, confirm, load],
  );

  return (
    <div className="flex h-svh flex-col bg-background">
      <div className="flex items-center gap-2 border-b px-4 py-3">
        <Button asChild variant="outline" size="icon-sm" title="Back to settings" aria-label="Back to settings">
          <Link to="/settings">
            <ChevronLeft className="size-4" />
          </Link>
        </Button>
        <Button asChild variant="outline" size="icon-sm" title="Back to chats" aria-label="Back to chats">
          <Link to="/">
            <Home className="size-4" />
          </Link>
        </Button>
        <div className="min-w-0 flex-1">
          <div className="truncate text-sm font-medium">Memory</div>
          <div className="truncate text-xs text-muted-foreground">
            Manage long-term memory records for each assistant
          </div>
        </div>
      </div>

      <div className="min-h-0 flex-1">
        <ScrollArea className="h-full">
          <div className="mx-auto w-full max-w-4xl space-y-4 px-4 py-6">
            <div className="rounded-lg border p-4">
              <div className="flex flex-wrap items-end gap-3">
                <div className="min-w-[220px] flex-1">
                  <div className="mb-1 text-xs font-medium">Assistant</div>
                  <Select value={assistantId} onValueChange={setAssistantId} disabled={busy || assistants.length === 0}>
                    <SelectTrigger>
                      <SelectValue placeholder="Select assistant" />
                    </SelectTrigger>
                    <SelectContent>
                      {assistants.map((assistant) => (
                        <SelectItem key={assistant.id} value={assistant.id}>
                          {assistant.name}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>

                <Button type="button" variant="outline" onClick={() => void load()} disabled={busy || !assistantId}>
                  <Save className="size-4" />
                  Refresh
                </Button>
              </div>
            </div>

            <div className="rounded-lg border p-4">
              <div className="text-sm font-semibold">Add Memory</div>
              <div className="mt-2 grid gap-2">
                <Input
                  placeholder="e.g. User prefers concise answers with examples"
                  value={newContent}
                  onChange={(e) => setNewContent(e.target.value)}
                  disabled={busy || !assistantId}
                />
                <div>
                  <Button type="button" size="sm" onClick={() => void createMemory()} disabled={busy || !assistantId}>
                    <Plus className="size-4" />
                    Add
                  </Button>
                </div>
              </div>
            </div>

            <div className="rounded-lg border p-4">
              <div className="flex items-center gap-2 text-sm font-semibold">
                <Brain className="size-4" />
                Memory Records ({items.length})
              </div>
              <div className="mt-2 text-xs text-muted-foreground">
                These records can be used by `memory_tool` during generation.
              </div>

              {loading ? <div className="mt-3 text-sm text-muted-foreground">Loading...</div> : null}

              {!loading && items.length === 0 ? (
                <div className="mt-3 rounded-md border border-dashed p-4 text-sm text-muted-foreground">
                  No memories.
                </div>
              ) : null}

              <div className="mt-3 space-y-2">
                {items.map((item) => (
                  <div key={item.id} className="rounded-md border p-3">
                    <div className="flex items-start gap-2">
                      <Textarea value={item.content} readOnly className="min-h-20 flex-1 text-sm" />
                      <div className="flex flex-col gap-2">
                        <Button type="button" size="icon-sm" variant="outline" onClick={() => void editMemory(item)} disabled={busy} title="Edit">
                          <Save className="size-4" />
                        </Button>
                        <Button
                          type="button"
                          size="icon-sm"
                          variant="destructive"
                          onClick={() => void deleteMemory(item)}
                          disabled={busy}
                          title="Delete"
                        >
                          <Trash2 className="size-4" />
                        </Button>
                      </div>
                    </div>
                    <div className="mt-1 text-xs text-muted-foreground">id: {item.id}</div>
                  </div>
                ))}
              </div>
            </div>
          </div>
        </ScrollArea>
      </div>
    </div>
  );
}
