export interface QaMeta {
  qaId: string;
  name: string;
  description: string | null;
  status: string;
  rolloutState: string;
  canaryPercent: number;
  revision: number;
  updatedAt: string | null;
}

export interface QaRow {
  entryId: string | null;
  question: string;
  variants: string[];
  answer: string;
  matchMode: string;
  priority: number;
  enabled: boolean;
  expiresAt: string | null;
  lastPrecheckAt?: string | null;
  lastPrecheckOk?: boolean | null;
}

export interface QaEntryInput {
  entry_id: string | null;
  question: string;
  variants: string[];
  answer: string;
  match_mode: string;
  priority: number;
  enabled: boolean;
  expires_at?: string;
}

export interface QaJob {
  publishId: string;
  status: string;
  snapshotRevision: number | null;
  errors: string | null;
  updatedAt: string | null;
}

export interface QaAckEntry {
  revision: number;
  status: string;
  updatedAt: string | null;
  errors: string | null;
}

export interface QaAckState {
  revision: number;
  acks: Record<string, QaAckEntry>;
}
