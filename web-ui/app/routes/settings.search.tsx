import * as React from "react";

import { Link } from "react-router";
import { ArrowDown, ArrowUp, ChevronLeft, Home, Plus, Save, Search, Trash2 } from "lucide-react";
import { toast } from "sonner";
import { v4 as uuidv4 } from "uuid";

import { Button } from "~/components/ui/button";
import { ScrollArea } from "~/components/ui/scroll-area";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "~/components/ui/select";
import { Switch } from "~/components/ui/switch";
import { Textarea } from "~/components/ui/textarea";
import api from "~/services/api";
import { useSettingsStore } from "~/stores";

export function meta() {
  return [{ title: "Settings - Search" }];
}

type AnyRecord = Record<string, unknown>;

type SearchServiceType =
  | "bing_local"
  | "rikkahub"
  | "zhipu"
  | "tavily"
  | "exa"
  | "searxng"
  | "linkup"
  | "brave"
  | "metaso"
  | "ollama"
  | "perplexity"
  | "firecrawl"
  | "jina"
  | "bocha";

const SEARCH_SERVICE_TYPES: Array<{ type: SearchServiceType; label: string }> = [
  { type: "bing_local", label: "Bing" },
  { type: "rikkahub", label: "RikkaHub" },
  { type: "zhipu", label: "Zhipu" },
  { type: "tavily", label: "Tavily" },
  { type: "exa", label: "Exa" },
  { type: "searxng", label: "SearXNG" },
  { type: "linkup", label: "LinkUp" },
  { type: "brave", label: "Brave" },
  { type: "metaso", label: "Metaso" },
  { type: "ollama", label: "Ollama" },
  { type: "perplexity", label: "Perplexity" },
  { type: "firecrawl", label: "Firecrawl" },
  { type: "jina", label: "Jina" },
  { type: "bocha", label: "Bocha" },
];

function safeStringify(value: unknown): string {
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return "";
  }
}

function parseJson(raw: string): unknown {
  const text = raw.trim();
  if (text.length === 0) return null;
  return JSON.parse(text) as unknown;
}

function readResultSize(settings: AnyRecord | null): number {
  const common = settings?.searchCommonOptions as AnyRecord | undefined;
  const value = common?.resultSize;
  if (typeof value === "number" && Number.isFinite(value) && value > 0) {
    return Math.floor(value);
  }
  return 10;
}

function createSearchServiceTemplate(type: SearchServiceType): AnyRecord {
  const id = uuidv4();

  switch (type) {
    case "bing_local":
      return { id, type };
    case "zhipu":
      return { id, type, apiKey: "" };
    case "tavily":
      return { id, type, apiKey: "", depth: "advanced" };
    case "exa":
      return { id, type, apiKey: "" };
    case "searxng":
      return { id, type, url: "", engines: "", language: "", username: "", password: "" };
    case "linkup":
      return { id, type, apiKey: "", depth: "standard" };
    case "brave":
      return { id, type, apiKey: "" };
    case "metaso":
      return { id, type, apiKey: "" };
    case "ollama":
      return { id, type, apiKey: "" };
    case "perplexity":
      return { id, type, apiKey: "", maxTokensPerPage: 1024 };
    case "firecrawl":
      return { id, type, apiKey: "" };
    case "jina":
      return { id, type, apiKey: "" };
    case "bocha":
      return { id, type, apiKey: "", summary: true };
    case "rikkahub":
      return { id, type, apiKey: "", depth: "standard" };
    default:
      return { id, type };
  }
}

function getServiceTypeLabel(type: unknown): string {
  if (typeof type !== "string") {
    return "Unknown";
  }
  const normalized = type.trim().toLowerCase();
  const match = SEARCH_SERVICE_TYPES.find((item) => item.type === normalized);
  return match?.label ?? normalized;
}

function moveIndexSelection(selected: number, from: number, to: number): number {
  if (selected === from) {
    return to;
  }
  if (from < to && selected > from && selected <= to) {
    return selected - 1;
  }
  if (from > to && selected >= to && selected < from) {
    return selected + 1;
  }
  return selected;
}

function buildServiceTitle(raw: string, index: number): string {
  try {
    const parsed = parseJson(raw);
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
      return `Service ${index + 1}`;
    }
    const obj = parsed as AnyRecord;
    const id = typeof obj.id === "string" ? obj.id : `#${index + 1}`;
    return `${getServiceTypeLabel(obj.type)} (${id})`;
  } catch {
    return `Service ${index + 1} (invalid JSON)`;
  }
}

export default function SettingsSearchPage() {
  const settings = useSettingsStore((state) => state.settings) as AnyRecord | null;
  const [busy, setBusy] = React.useState(false);
  const [dirty, setDirty] = React.useState(false);
  const [addType, setAddType] = React.useState<SearchServiceType>("bing_local");
  const [serviceTexts, setServiceTexts] = React.useState<string[]>(() =>
    Array.isArray(settings?.searchServices) ? settings.searchServices.map((service) => safeStringify(service)) : [],
  );
  const [selectedDraft, setSelectedDraft] = React.useState<number>(
    typeof settings?.searchServiceSelected === "number" ? settings.searchServiceSelected : 0,
  );
  const [resultSizeDraft, setResultSizeDraft] = React.useState<string>(() => String(readResultSize(settings)));

  const enabled = settings?.enableWebSearch === true;

  React.useEffect(() => {
    if (dirty) return;
    const services = Array.isArray(settings?.searchServices) ? settings.searchServices : [];
    setServiceTexts(services.map((service) => safeStringify(service)));
    setSelectedDraft(typeof settings?.searchServiceSelected === "number" ? settings.searchServiceSelected : 0);
    setResultSizeDraft(String(readResultSize(settings)));
  }, [settings?.searchServiceSelected, settings?.searchServices, settings?.searchCommonOptions, settings, dirty]);

  const handleToggle = React.useCallback(async () => {
    if (!settings) return;

    setBusy(true);
    try {
      await api.post<{ status: string }>("settings/search/enabled", { enabled: !enabled });
      toast.success(!enabled ? "Web search enabled" : "Web search disabled");
    } catch (error) {
      console.error("settings/search/enabled failed", error);
      toast.error(error instanceof Error ? error.message : "Update failed");
    } finally {
      setBusy(false);
    }
  }, [enabled, settings]);

  const handleReset = React.useCallback(() => {
    if (!settings) return;
    const services = Array.isArray(settings.searchServices) ? settings.searchServices : [];
    setServiceTexts(services.map((service) => safeStringify(service)));
    setSelectedDraft(typeof settings.searchServiceSelected === "number" ? settings.searchServiceSelected : 0);
    setResultSizeDraft(String(readResultSize(settings)));
    setDirty(false);
  }, [settings]);

  const handleAddService = React.useCallback(() => {
    const nextService = createSearchServiceTemplate(addType);
    setServiceTexts((prev) => [safeStringify(nextService), ...prev]);
    setSelectedDraft(0);
    setDirty(true);
    toast.success("Search service added");
  }, [addType]);

  const handleDeleteService = React.useCallback(
    (index: number) => {
      if (serviceTexts.length <= 1) {
        toast.error("At least one search service is required");
        return;
      }
      const next = serviceTexts.filter((_, idx) => idx !== index);
      setServiceTexts(next);
      setSelectedDraft((prev) => {
        if (prev === index) return Math.min(index, next.length - 1);
        if (index < prev) return prev - 1;
        return prev;
      });
      setDirty(true);
    },
    [serviceTexts],
  );

  const handleMoveService = React.useCallback(
    (from: number, to: number) => {
      if (to < 0 || to >= serviceTexts.length) return;
      const next = serviceTexts.slice();
      const [item] = next.splice(from, 1);
      next.splice(to, 0, item);
      setServiceTexts(next);
      setSelectedDraft((prev) => moveIndexSelection(prev, from, to));
      setDirty(true);
    },
    [serviceTexts],
  );

  const handleSave = React.useCallback(async () => {
    if (!settings) return;
    if (serviceTexts.length === 0) {
      toast.error("At least one search service is required");
      return;
    }

    const parsedServices: AnyRecord[] = [];
    for (let i = 0; i < serviceTexts.length; i += 1) {
      const text = serviceTexts[i];
      let parsed: unknown;
      try {
        parsed = parseJson(text);
      } catch {
        toast.error(`Service #${i + 1} JSON is invalid`);
        return;
      }
      if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
        toast.error(`Service #${i + 1} must be a JSON object`);
        return;
      }
      const nextService = { ...(parsed as AnyRecord) };
      if (typeof nextService.id !== "string" || nextService.id.trim().length === 0) {
        nextService.id = uuidv4();
      }
      if (typeof nextService.type === "string") {
        nextService.type = nextService.type.trim().toLowerCase();
      }
      parsedServices.push(nextService);
    }

    const resultSize = Number.parseInt(resultSizeDraft, 10);
    if (Number.isNaN(resultSize) || resultSize <= 0) {
      toast.error("Result size must be a positive integer");
      return;
    }

    const selected = Math.max(0, Math.min(selectedDraft, parsedServices.length - 1));
    const common = ((settings.searchCommonOptions as AnyRecord | undefined) ?? {}) as AnyRecord;

    const nextSettings = {
      ...settings,
      searchServices: parsedServices,
      searchServiceSelected: selected,
      searchCommonOptions: {
        ...common,
        resultSize,
      },
    };

    setBusy(true);
    try {
      await api.post<{ status: string }>("settings/replace", nextSettings);
      setServiceTexts(parsedServices.map((service) => safeStringify(service)));
      setSelectedDraft(selected);
      setResultSizeDraft(String(resultSize));
      setDirty(false);
      toast.success("Search settings saved");
    } catch (error) {
      console.error("settings/replace failed", error);
      toast.error(error instanceof Error ? error.message : "Save failed");
    } finally {
      setBusy(false);
    }
  }, [resultSizeDraft, selectedDraft, serviceTexts, settings]);

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
          <div className="truncate text-sm font-medium">Search</div>
          <div className="truncate text-xs text-muted-foreground">Manage search providers and common options</div>
        </div>
        <Button type="button" variant="outline" size="sm" disabled={busy || !settings} onClick={handleReset}>
          Reset
        </Button>
        <Button type="button" variant="default" size="sm" disabled={busy || !settings} onClick={() => void handleSave()}>
          <Save className="size-4" />
          Save
        </Button>
      </div>

      <div className="min-h-0 flex-1">
        <ScrollArea className="h-full">
          <div className="mx-auto w-full max-w-4xl space-y-4 px-4 py-6">
            <div className="rounded-lg border p-4">
              <div className="flex items-center justify-between gap-3">
                <div>
                  <div className="text-sm font-semibold">Enable Web Search</div>
                  <div className="mt-1 text-xs text-muted-foreground">
                    Controls whether search tools are offered to the model.
                  </div>
                </div>
                <Switch checked={enabled} disabled={busy || !settings} onCheckedChange={() => void handleToggle()} />
              </div>
            </div>

            <div className="rounded-lg border p-4">
              <div className="flex items-center gap-2 text-sm font-semibold">
                <Search className="size-4" />
                Search Services
              </div>
              <div className="mt-2 text-xs text-muted-foreground">
                Select active provider index and configure provider list.
              </div>

              <div className="mt-3 grid gap-3 md:grid-cols-2">
                <div>
                  <div className="mb-2 text-xs text-muted-foreground">Active Provider</div>
                  <Select
                    value={String(selectedDraft)}
                    onValueChange={(value) => {
                      const index = Number.parseInt(value, 10);
                      if (Number.isNaN(index)) return;
                      setSelectedDraft(index);
                      setDirty(true);
                    }}
                    disabled={busy || !settings || serviceTexts.length === 0}
                  >
                    <SelectTrigger className="w-full">
                      <SelectValue placeholder="Select a service" />
                    </SelectTrigger>
                    <SelectContent>
                      {serviceTexts.length === 0 ? (
                        <SelectItem value="0" disabled>
                          No services
                        </SelectItem>
                      ) : (
                        serviceTexts.map((raw, index) => (
                          <SelectItem key={`service-${index}`} value={String(index)}>
                            {buildServiceTitle(raw, index)}
                          </SelectItem>
                        ))
                      )}
                    </SelectContent>
                  </Select>
                </div>

                <div>
                  <div className="mb-2 text-xs text-muted-foreground">Search Result Size</div>
                  <input
                    type="number"
                    min={1}
                    value={resultSizeDraft}
                    onChange={(event) => {
                      setResultSizeDraft(event.target.value);
                      setDirty(true);
                    }}
                    disabled={busy || !settings}
                    className="h-9 w-full rounded-md border bg-background px-3 text-sm"
                  />
                </div>
              </div>

              <div className="mt-4 flex flex-col gap-2 md:flex-row md:items-center">
                <Select value={addType} onValueChange={(value) => setAddType(value as SearchServiceType)} disabled={busy || !settings}>
                  <SelectTrigger className="w-full">
                    <SelectValue placeholder="Select provider type" />
                  </SelectTrigger>
                  <SelectContent>
                    {SEARCH_SERVICE_TYPES.map((serviceType) => (
                      <SelectItem key={serviceType.type} value={serviceType.type}>
                        {serviceType.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                <Button type="button" variant="secondary" size="sm" disabled={busy || !settings} onClick={handleAddService}>
                  <Plus className="size-4" />
                  Add Provider
                </Button>
              </div>

              <div className="mt-2 text-xs text-muted-foreground">Save is required to persist provider changes.</div>
            </div>

            <div className="space-y-3 rounded-lg border p-4">
              <div className="text-sm font-semibold">Providers</div>
              {serviceTexts.length === 0 ? (
                <div className="rounded-md border border-dashed px-3 py-8 text-center text-sm text-muted-foreground">
                  No providers configured.
                </div>
              ) : (
                serviceTexts.map((raw, index) => (
                  <div key={`provider-${index}`} className="rounded-md border p-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <div className="min-w-0 flex-1 text-sm font-medium">{buildServiceTitle(raw, index)}</div>
                      <Button
                        type="button"
                        size="sm"
                        variant={selectedDraft === index ? "default" : "outline"}
                        disabled={busy || !settings}
                        onClick={() => {
                          setSelectedDraft(index);
                          setDirty(true);
                        }}
                      >
                        Active
                      </Button>
                      <Button
                        type="button"
                        size="icon-sm"
                        variant="outline"
                        disabled={busy || !settings || index === 0}
                        onClick={() => handleMoveService(index, index - 1)}
                        title="Move up"
                        aria-label="Move up"
                      >
                        <ArrowUp className="size-4" />
                      </Button>
                      <Button
                        type="button"
                        size="icon-sm"
                        variant="outline"
                        disabled={busy || !settings || index === serviceTexts.length - 1}
                        onClick={() => handleMoveService(index, index + 1)}
                        title="Move down"
                        aria-label="Move down"
                      >
                        <ArrowDown className="size-4" />
                      </Button>
                      <Button
                        type="button"
                        size="icon-sm"
                        variant="destructive"
                        disabled={busy || !settings || serviceTexts.length <= 1}
                        onClick={() => handleDeleteService(index)}
                        title="Delete provider"
                        aria-label="Delete provider"
                      >
                        <Trash2 className="size-4" />
                      </Button>
                    </div>

                    <Textarea
                      value={raw}
                      onChange={(event) => {
                        const next = serviceTexts.slice();
                        next[index] = event.target.value;
                        setServiceTexts(next);
                        setDirty(true);
                      }}
                      className="mt-3 min-h-[11rem] font-mono text-xs"
                      spellCheck={false}
                    />
                  </div>
                ))
              )}
            </div>

            {dirty ? <div className="text-xs text-muted-foreground">Unsaved changes.</div> : null}
          </div>
        </ScrollArea>
      </div>
    </div>
  );
}

