import * as React from "react";

import { Link } from "react-router";
import { ChevronLeft, Download, Home, Upload } from "lucide-react";
import { toast } from "sonner";

import { Button } from "~/components/ui/button";
import { useConfirm } from "~/components/confirm-dialog-provider";
import { ScrollArea } from "~/components/ui/scroll-area";
import { Textarea } from "~/components/ui/textarea";
import { cn } from "~/lib/utils";
import api, { appendWebAuthQuery } from "~/services/api";

interface MigrationImportReportDto {
  success: boolean;
  rolledBack: boolean;
  message: string;
  importedConversations: number;
  importedMessageNodes: number;
  importedFiles: number;
}

export function meta() {
  return [{ title: "Settings - Backup" }];
}

function formatReport(report: MigrationImportReportDto | null): string {
  if (!report) return "";
  return JSON.stringify(report, null, 2);
}

export default function SettingsBackupPage() {
  const [importBusy, setImportBusy] = React.useState(false);
  const [importFile, setImportFile] = React.useState<File | null>(null);
  const [report, setReport] = React.useState<MigrationImportReportDto | null>(null);
  const confirm = useConfirm();

  const handleImport = React.useCallback(async () => {
    if (!importFile) {
      toast.error("Please select a .zip backup file");
      return;
    }

    const formData = new FormData();
    formData.append("file", importFile);

    setImportBusy(true);
    try {
      const result = await api.postMultipart<MigrationImportReportDto>("migration/import", formData);
      setReport(result);
      toast.success(result.success ? "Import completed" : "Import failed");
      if (result.success) {
        setImportFile(null);
      }
    } catch (error) {
      console.error("migration/import failed", error);
      toast.error(error instanceof Error ? error.message : "Import failed");
    } finally {
      setImportBusy(false);
    }
  }, [importFile]);

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
          <div className="truncate text-sm font-medium">Backup</div>
          <div className="truncate text-xs text-muted-foreground">Import/export portable backup zip</div>
        </div>
      </div>

      <div className="min-h-0 flex-1">
        <ScrollArea className="h-full">
          <div className="mx-auto w-full max-w-3xl space-y-4 px-4 py-6">
            <div className="rounded-lg border p-4">
              <div className="text-sm font-semibold">Export</div>
              <div className="mt-1 text-xs text-muted-foreground">
                Download a portable backup zip (settings + db + uploads).
              </div>
              <div className="mt-3">
                <Button asChild variant="secondary" size="sm">
                  <a href={appendWebAuthQuery("/api/migration/export")}>
                    <Download className="size-4" />
                    Download Export
                  </a>
                </Button>
              </div>
            </div>

            <div className="rounded-lg border p-4">
              <div className="text-sm font-semibold">Import</div>
              <div className="mt-1 text-xs text-muted-foreground">
                Import an Android/Web backup zip. This overwrites current data. Backend will auto-backup and rollback on failure.
              </div>
              <div className="mt-3 flex flex-col gap-2">
                <input
                  type="file"
                  accept=".zip,application/zip"
                  onChange={(e) => {
                    const file = e.target.files?.[0] ?? null;
                    setImportFile(file);
                  }}
                />
                <div className="flex items-center gap-2">
                  <Button
                    variant="destructive"
                    size="sm"
                    disabled={importBusy || !importFile}
                    onClick={() => {
                      void (async () => {
                        const confirmed = await confirm({
                          title: "Import backup?",
                          description: "Import will overwrite current data. Continue?",
                          confirmText: "Import",
                          cancelText: "Cancel",
                          destructive: true,
                        });
                        if (!confirmed) return;
                        void handleImport();
                      })();
                    }}
                  >
                    <Upload className={cn("size-4", importBusy && "opacity-60")} />
                    Import
                  </Button>
                  <div className="text-xs text-muted-foreground">
                    {importFile ? importFile.name : "No file selected"}
                  </div>
                </div>
              </div>
            </div>

            <div className="rounded-lg border p-4">
              <div className="text-sm font-semibold">Import Report</div>
              <div className="mt-1 text-xs text-muted-foreground">
                Response from backend /api/migration/import
              </div>
              <div className="mt-3">
                <Textarea value={formatReport(report)} readOnly className="min-h-40 font-mono text-xs" />
              </div>
            </div>
          </div>
        </ScrollArea>
      </div>
    </div>
  );
}