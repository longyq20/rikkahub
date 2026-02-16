import * as React from "react";

import { Button } from "~/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "~/components/ui/dialog";
import { Input } from "~/components/ui/input";

type ConfirmOptions = {
  title?: string;
  description?: string;
  confirmText?: string;
  cancelText?: string;
  destructive?: boolean;
};

type PromptOptions = {
  title?: string;
  description?: string;
  confirmText?: string;
  cancelText?: string;
  placeholder?: string;
  defaultValue?: string;
  destructive?: boolean;
};

type ConfirmFn = (options: ConfirmOptions) => Promise<boolean>;
type PromptFn = (options: PromptOptions) => Promise<string | null>;

type ConfirmState = {
  kind: "confirm";
  title: string;
  description: string;
  confirmText: string;
  cancelText: string;
  destructive: boolean;
  resolve: (confirmed: boolean) => void;
};

type PromptState = {
  kind: "prompt";
  title: string;
  description: string;
  confirmText: string;
  cancelText: string;
  placeholder: string;
  destructive: boolean;
  resolve: (value: string | null) => void;
};

type DialogState = ConfirmState | PromptState;

const ConfirmContext = React.createContext<ConfirmFn | null>(null);
const PromptContext = React.createContext<PromptFn | null>(null);

export function ConfirmDialogProvider({ children }: { children: React.ReactNode }) {
  const [state, setState] = React.useState<DialogState | null>(null);
  const [promptValue, setPromptValue] = React.useState("");

  const closeWithCancel = React.useCallback(() => {
    setState((current) => {
      if (!current) return null;
      if (current.kind === "confirm") {
        current.resolve(false);
      } else {
        current.resolve(null);
      }
      return null;
    });
  }, []);

  const submitCurrent = React.useCallback(() => {
    setState((current) => {
      if (!current) return null;
      if (current.kind === "confirm") {
        current.resolve(true);
      } else {
        current.resolve(promptValue);
      }
      return null;
    });
  }, [promptValue]);

  React.useEffect(() => {
    return () => {
      setState((current) => {
        if (!current) return null;
        if (current.kind === "confirm") {
          current.resolve(false);
        } else {
          current.resolve(null);
        }
        return null;
      });
    };
  }, []);

  const confirm = React.useCallback<ConfirmFn>((options) => {
    return new Promise<boolean>((resolve) => {
      setState({
        kind: "confirm",
        title: options.title?.trim() || "Please Confirm",
        description: options.description?.trim() || "",
        confirmText: options.confirmText?.trim() || "Confirm",
        cancelText: options.cancelText?.trim() || "Cancel",
        destructive: options.destructive === true,
        resolve,
      });
    });
  }, []);

  const prompt = React.useCallback<PromptFn>((options) => {
    return new Promise<string | null>((resolve) => {
      setPromptValue(options.defaultValue ?? "");
      setState({
        kind: "prompt",
        title: options.title?.trim() || "Input Required",
        description: options.description?.trim() || "",
        confirmText: options.confirmText?.trim() || "Confirm",
        cancelText: options.cancelText?.trim() || "Cancel",
        placeholder: options.placeholder ?? "",
        destructive: options.destructive === true,
        resolve,
      });
    });
  }, []);

  return (
    <ConfirmContext.Provider value={confirm}>
      <PromptContext.Provider value={prompt}>
        {children}
        <Dialog
          open={state !== null}
          onOpenChange={(open) => {
            if (!open) {
              closeWithCancel();
            }
          }}
        >
          <DialogContent className="max-w-md">
            <DialogHeader>
              <DialogTitle>{state?.title ?? "Please Confirm"}</DialogTitle>
              {state?.description ? <DialogDescription>{state.description}</DialogDescription> : null}
            </DialogHeader>

            {state?.kind === "prompt" ? (
              <Input
                autoFocus
                value={promptValue}
                onChange={(event) => setPromptValue(event.target.value)}
                placeholder={state.placeholder}
                onKeyDown={(event) => {
                  if (event.key === "Enter") {
                    event.preventDefault();
                    submitCurrent();
                  }
                }}
              />
            ) : null}

            <DialogFooter>
              <Button type="button" variant="outline" onClick={closeWithCancel}>
                {state?.cancelText ?? "Cancel"}
              </Button>
              <Button
                type="button"
                variant={state?.destructive ? "destructive" : "default"}
                onClick={submitCurrent}
              >
                {state?.confirmText ?? "Confirm"}
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      </PromptContext.Provider>
    </ConfirmContext.Provider>
  );
}

export function useConfirm(): ConfirmFn {
  const context = React.useContext(ConfirmContext);
  if (!context) {
    throw new Error("useConfirm must be used within ConfirmDialogProvider");
  }
  return context;
}

export function usePrompt(): PromptFn {
  const context = React.useContext(PromptContext);
  if (!context) {
    throw new Error("usePrompt must be used within ConfirmDialogProvider");
  }
  return context;
}