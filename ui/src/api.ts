export type VaultState = "UNINITIALIZED" | "LOCKED" | "UNLOCKED";

export type AccountType = "CHECKING" | "LIVRET_A" | "LDDS" | "SAVINGS" | "PEA" | "LIFE_INSURANCE" | "PEE";

/** Avoirs disponibles à une date (épargne salariale). */
export type Availability = { availableOn: string; valueCents: number };

export type SecurityKind = "STOCK" | "FUND" | "ETF" | "EURO_FUND" | "OTHER";

export type TransactionKind =
  | "CARD"
  | "TRANSFER"
  | "DIRECT_DEBIT"
  | "DEPOSIT"
  | "WITHDRAWAL"
  | "FEE"
  | "OTHER";

export type VaultStatus = { state: VaultState; minPasswordLength: number };

export type Account = {
  id: number;
  institution: string;
  type: AccountType;
  name: string;
  ibanMasked: string;
  balanceCents: number | null;
  balanceDate: string | null;
  transactionCount: number;
};

export type Transaction = {
  id: number;
  accountId: number;
  accountName: string;
  bookingDate: string;
  operationDate: string;
  amountCents: number;
  label: string;
  details: string;
  kind: TransactionKind;
  internalTransfer: boolean;
  categoryId: number | null;
  categoryName: string | null;
  categoryKind: CategoryKind | null;
  categorySource: "MANUAL" | "INTERNAL" | "RULE" | "KIND" | "SPLIT" | null;
  suggestedPattern: string;
  note: string | null;
  splits: { categoryId: number; categoryName: string; categoryKind: CategoryKind; amountCents: number }[];
};

export type RecurringSeries = {
  key: string;
  pattern: string;
  label: string;
  categoryName: string | null;
  categoryKind: CategoryKind | null;
  credit: boolean;
  occurrences: number;
  firstDate: string;
  lastDate: string;
  intervalDays: number;
  monthly: boolean;
  nextDate: string;
  lastAmountCents: number;
  previousAmountCents: number | null;
  typicalAmountCents: number;
  variable: boolean;
  status: "ACTIVE" | "STOPPED";
  isNew: boolean;
  priceChanged: boolean;
};

export type RecurringOverview = {
  horizon: string | null;
  monthlyFixedCents: number;
  monthlyIncomeCents: number;
  series: RecurringSeries[];
  ignored: RecurringSeries[];
};

export type BudgetAlert = {
  categoryId: number;
  name: string;
  spentCents: number;
  budgetCents: number;
  projectedCents: number;
  status: "WATCH" | "AT_RISK" | "OVER";
};

export type MonthProjection = {
  month: string;
  asOf: string | null;
  daysRemaining: number;
  actualIncomeCents: number;
  actualExpenseCents: number;
  actualSavingsCents: number;
  expected: { label: string; date: string; amountCents: number; savings: boolean }[];
  variableEstimateCents: number;
  projectedIncomeCents: number;
  projectedExpenseCents: number;
  projectedSavingsCents: number;
  checkingBalanceCents: number | null;
  projectedCheckingCents: number | null;
  budgetAlerts: BudgetAlert[];
};

export type CategoryKind = "EXPENSE" | "INCOME" | "SAVINGS" | "TRANSFER";

export type Category = {
  id: number;
  parentId: number | null;
  name: string;
  kind: CategoryKind;
  system: boolean;
  budgetCents: number | null;
  ruleCount: number;
  transactionCount: number;
};

export type Rule = {
  id: number;
  pattern: string;
  matchType: "CONTAINS" | "REGEX";
  scope: "LABEL" | "LABEL_AND_DETAILS";
  categoryId: number;
  categoryName: string;
  priority: number;
};

export type CategoryLine = {
  categoryId: number | null;
  name: string;
  cents: number;
  budgetCents: number | null;
  transactionCount: number;
  children: CategoryLine[];
};

export type MonthSummary = {
  month: string;
  incomeCents: number;
  expenseCents: number;
  savingsCents: number;
  remainingCents: number;
  savingsRate: number | null;
  expenses: CategoryLine[];
  income: CategoryLine[];
  uncategorizedCount: number;
  internalTransferCount: number;
};

export type SummaryHistory = {
  months: { month: string; incomeCents: number; expenseCents: number; savingsCents: number }[];
  averageIncomeCents: number;
  averageExpenseCents: number;
  averageSavingsCents: number;
  averageSavingsRate: number | null;
};

export type AnnualLine = { categoryId: number | null; name: string; cents: number; previousCents: number; deltaCents: number };
export type AnnualTotals = { incomeCents: number; expenseCents: number; savingsCents: number; savingsRate: number | null };
export type AnnualReview = {
  year: number;
  through: string;
  current: AnnualTotals;
  previous: AnnualTotals;
  expenses: AnnualLine[];
  income: AnnualLine[];
  increases: AnnualLine[];
  decreases: AnnualLine[];
  months: { month: string; incomeCents: number; expenseCents: number; previousIncomeCents: number; previousExpenseCents: number }[];
  monthsWithData: number;
};

export type Goal = {
  id: number;
  name: string;
  targetCents: number;
  targetDate: string | null;
  accountId: number | null;
  accountName: string | null;
  source: "ACCOUNT" | "MANUAL";
  currentCents: number;
  monthlyCents: number | null;
  reached: boolean;
  monthsLeft: number | null;
  requiredMonthlyCents: number | null;
  projectedMonth: string | null;
};

export type GoalRequest = {
  name: string;
  targetCents: number;
  targetDate?: string | null;
  accountId?: number | null;
  savedCents?: number | null;
  monthlyCents?: number | null;
};

export type AccountPreview = {
  key: number;
  type: AccountType;
  name: string;
  ibanMasked: string;
  newAccount: boolean;
  openingDate: string;
  openingCents: number;
  closingDate: string;
  closingCents: number;
  expectedClosingCents: number;
  balanceOk: boolean;
  transactionCount: number;
  newTransactionCount: number;
  duplicateCount: number;
};

export type PreviewTransaction = {
  accountKey: number;
  bookingDate: string;
  operationDate: string;
  amountCents: number;
  label: string;
  kind: TransactionKind;
  duplicate: boolean;
  internalTransfer: boolean;
};

export type PortfolioPreview = {
  type: AccountType;
  name: string;
  numberMasked: string;
  newAccount: boolean;
  valuationDate: string;
  printedTotalCents: number;
  computedTotalCents: number;
  balanced: boolean;
  cashCents: number;
  contributionsCents: number | null;
  availability: Availability[];
  positions: {
    isin: string | null;
    name: string;
    kind: SecurityKind;
    quantity: string | null;
    unitPrice: string | null;
    valueCents: number;
    unrealizedCents: number | null;
  }[];
};

export type Holding = {
  isin: string | null;
  name: string;
  kind: SecurityKind;
  quantity: string | null;
  statementValueCents: number;
  unrealizedCents: number | null;
  price: string | null;
  priceDate: string | null;
  currentValueCents: number;
  repriced: boolean;
};

export type Envelope = {
  accountId: number;
  type: AccountType;
  name: string;
  institution: string;
  numberMasked: string;
  statementDate: string;
  statementValueCents: number;
  cashCents: number;
  valueCents: number;
  valueSource: "STATEMENT" | "ESTIMATED" | "MANUAL";
  valueDate: string;
  contributionsCents: number | null;
  gainCents: number | null;
  gainRatio: number | null;
  holdings: Holding[];
  availability: Availability[];
  performance: EnvelopePerformance | null;
};

export type NetWorth = {
  totalCents: number;
  liquidCents: number;
  investedCents: number;
  cashAccounts: { accountId: number; type: AccountType; name: string; balanceCents: number; balanceDate: string }[];
  envelopes: Envelope[];
  history: { date: string; totalCents: number; estimated: boolean }[];
  monthly: { month: string; date: string; liquidCents: number; investedCents: number; totalCents: number; estimated: boolean }[];
  allocation: { assetClass: AssetClass; valueCents: number }[];
};

export type Milestone = {
  accountId: number;
  accountName: string;
  accountType: AccountType;
  kind: "PEA_MATURITY" | "LIFE_INSURANCE_MATURITY" | "PEE_RELEASE" | "PEA_CEILING" | "SAVINGS_CEILING";
  status: "NEEDS_OPENING_DATE" | "UPCOMING" | "SOON" | "REACHED";
  date: string | null;
  openedOn: string | null;
  amountCents: number | null;
  limitCents: number | null;
};

export type MilestoneReport = {
  milestones: Milestone[];
  rules: {
    verifiedOn: string;
    peaCeilingCents: number;
    livretACeilingCents: number;
    lddsCeilingCents: number;
    lifeInsuranceAllowanceSingleCents: number;
    lifeInsuranceAllowanceCoupleCents: number;
    socialLeviesGeneral: number;
    socialLeviesLifeInsurance: number;
  };
};

export type AssetClass = "CURRENT_ACCOUNTS" | "SAVINGS_ACCOUNTS" | "ENVELOPE_CASH" | "MONEY_MARKET" | "EURO_FUNDS" | "EQUITY_AND_FUNDS" | "UNALLOCATED";

export type EnvelopePerformance = {
  sinceDate: string;
  untilDate: string;
  startValueCents: number;
  netContributionsCents: number;
  endValueCents: number;
  gainCents: number;
  periodRate: number;
  annualizedRate: number | null;
};

export type PriceReport = {
  checked: number;
  found: number;
  results: {
    isin: string;
    name: string;
    status: "FOUND" | "NOT_FOUND" | "MISMATCH" | "ERROR" | "UNAVAILABLE";
    symbol: string | null;
    lastPrice: string | null;
    lastDate: string | null;
  }[];
};

export type ImportPreview = {
  previewId: string | null;
  status: "READY" | "REJECTED" | "ALREADY_IMPORTED";
  parserId: string | null;
  institution: string | null;
  periodStart: string | null;
  periodEnd: string | null;
  accounts: AccountPreview[];
  transactions: PreviewTransaction[];
  issues: string[];
  documentType: "ACCOUNT_STATEMENT" | "PORTFOLIO";
  portfolio: PortfolioPreview | null;
};

export type ImportRecord = {
  id: number;
  importedAt: string;
  importedBy: string;
  parserId: string;
  periodStart: string;
  periodEnd: string;
  impact: {
    accountsTouched: number;
    accountsCreated: number;
    transactionsCreated: number;
    duplicatesSkipped: number;
    internalTransfers: number;
    positionsRecorded: number;
  };
  cancelledAt: string | null;
  cancellable: boolean;
};

export type CsvMapping = {
  headerRow: number | null;
  firstDataRow: number;
  dateColumn: number;
  operationDateColumn: number | null;
  labelColumn: number;
  detailColumns: number[];
  amountColumn: number | null;
  debitColumn: number | null;
  creditColumn: number | null;
  balanceColumn: number | null;
  dateFormat: string;
  decimalComma: boolean;
};

export type TabularAnalysis = {
  token: string | null;
  alreadyImported: boolean;
  format: "CSV" | "OFX";
  encoding: string;
  delimiter: string | null;
  rows: string[][];
  rowCount: number;
  columnCount: number;
  suggestedMapping: CsvMapping | null;
  mappingRemembered: boolean;
  ofx: {
    accountMasked: string | null;
    transactionCount: number;
    firstDate: string | null;
    lastDate: string | null;
    closingCents: number | null;
    closingDate: string | null;
  } | null;
  suggestedAccountId: number | null;
};

export type TabularPreviewRequest = {
  mapping?: CsvMapping;
  account: { existingAccountId?: number; name?: string; type?: AccountType; institution?: string };
  openingCents?: number;
  closingCents?: number;
};

export type RuleSuggestion = { pattern: string; count: number; totalCents: number; sampleLabel: string; lastDate: string };

export type AutoLockSetting = { minutes: number; allowed: number[] };
export type UpdateSetting = { checkAtStartup: boolean };

export type BackupKind = "MANUAL" | "AUTOMATIC" | "SAFETY";

export type Backup = { name: string; kind: BackupKind; createdAt: string; sizeBytes: number };

export type BackupList = { directory: string; backups: Backup[] };

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

/** Le coffre s'est verrouillé côté cœur : l'application revient à l'écran de déverrouillage. */
export const VAULT_LOCKED_EVENT = "budgetly:vault-locked";

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, { credentials: "same-origin", ...init });
  if (response.status === 204) return undefined as T;
  const body = await response.json().catch(() => null);
  if (!response.ok) {
    if (response.status === 423) window.dispatchEvent(new Event(VAULT_LOCKED_EVENT));
    throw new ApiError(
      response.status,
      body?.error ?? "ERROR",
      body?.message ?? `Le cœur a répondu ${response.status}.`,
    );
  }
  return body as T;
}

const json = (body: unknown): RequestInit => ({
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify(body),
});

export const api = {
  vault: () => request<VaultStatus>("/api/vault"),
  setup: (password: string) => request<void>("/api/vault/setup", json({ password })),
  unlock: (password: string) => request<void>("/api/vault/unlock", json({ password })),
  lock: () => request<void>("/api/vault/lock", { method: "POST" }),
  changePassword: (currentPassword: string, newPassword: string) =>
    request<void>("/api/vault/password", json({ currentPassword, newPassword })),
  restore: (file: File, password: string) => {
    const form = new FormData();
    form.append("file", file);
    form.append("password", password);
    return request<void>("/api/vault/restore", { method: "POST", body: form });
  },
  backups: () => request<BackupList>("/api/backups"),
  createBackup: () => request<Backup>("/api/backups", { method: "POST" }),
  deleteBackup: (name: string) => request<void>(`/api/backups/${encodeURIComponent(name)}`, { method: "DELETE" }),
  backupUrl: (name: string) => `/api/backups/${encodeURIComponent(name)}`,
  csvUrl: "/api/transactions/export.csv",

  accounts: () => request<Account[]>("/api/accounts"),
  goals: () => request<Goal[]>("/api/goals"),
  annual: (year: number) => request<AnnualReview>(`/api/summary/year?year=${year}`),
  annualPdfUrl: (year: number) => `/api/summary/year/report.pdf?year=${year}`,
  createGoal: (body: GoalRequest) => request<{ id: number }>("/api/goals", json(body)),
  updateGoal: (id: number, body: GoalRequest) => request<void>(`/api/goals/${id}`, { ...json(body), method: "PUT" }),
  deleteGoal: (id: number) => request<void>(`/api/goals/${id}`, { method: "DELETE" }),
  milestones: () => request<MilestoneReport>("/api/milestones"),
  setOpenedOn: (accountId: number, date: string | null) =>
    request<void>(`/api/accounts/${accountId}/opened-on`, { ...json({ date }), method: "PUT" }),
  transactions: (filter: {
    accountId?: number;
    from?: string;
    to?: string;
    q?: string;
    categoryId?: number;
    uncategorized?: boolean;
  }) => {
    const params = new URLSearchParams();
    if (filter.categoryId) params.set("categoryId", String(filter.categoryId));
    if (filter.uncategorized) params.set("uncategorized", "true");
    if (filter.accountId) params.set("accountId", String(filter.accountId));
    if (filter.from) params.set("from", filter.from);
    if (filter.to) params.set("to", filter.to);
    if (filter.q) params.set("q", filter.q);
    return request<Transaction[]>(`/api/transactions?${params}`);
  },

  preview: (file: File) => {
    const form = new FormData();
    form.append("file", file);
    return request<ImportPreview>("/api/imports/preview", { method: "POST", body: form });
  },
  analyzeTabular: (file: File) => {
    const form = new FormData();
    form.append("file", file);
    return request<TabularAnalysis>("/api/imports/tabular/analyze", { method: "POST", body: form });
  },
  previewTabular: (token: string, body: TabularPreviewRequest) =>
    request<ImportPreview>(`/api/imports/tabular/${token}/preview`, json(body)),
  commit: (previewId: string) => request<ImportRecord>(`/api/imports/${previewId}/commit`, { method: "POST" }),
  imports: () => request<ImportRecord[]>("/api/imports"),
  cancelImport: (id: number) => request<void>(`/api/imports/${id}`, { method: "DELETE" }),

  categories: () => request<Category[]>("/api/categories"),
  createCategory: (body: { name: string; parentId?: number | null; kind?: CategoryKind }) =>
    request<{ id: number }>("/api/categories", json(body)),
  renameCategory: (id: number, name: string) =>
    request<void>(`/api/categories/${id}`, { ...json({ name }), method: "PATCH" }),
  deleteCategory: (id: number) => request<void>(`/api/categories/${id}`, { method: "DELETE" }),
  setBudget: (id: number, monthlyCents: number | null) =>
    request<void>(`/api/categories/${id}/budget`, { ...json({ monthlyCents }), method: "PUT" }),
  rules: () => request<Rule[]>("/api/rules"),
  ruleSuggestions: () => request<RuleSuggestion[]>("/api/rules/suggestions"),
  categorizeMany: (transactionIds: number[], categoryId: number | null) =>
    request<{ updated: number }>("/api/transactions/category", { ...json({ transactionIds, categoryId }), method: "PUT" }),
  autoLock: () => request<AutoLockSetting>("/api/vault/auto-lock"),
  setAutoLock: (minutes: number) => request<AutoLockSetting>("/api/vault/auto-lock", { ...json({ minutes }), method: "PUT" }),
  updateSetting: () => request<UpdateSetting>("/api/settings/updates"),
  setUpdateSetting: (checkAtStartup: boolean) =>
    request<UpdateSetting>("/api/settings/updates", { ...json({ checkAtStartup }), method: "PUT" }),
  activity: () => request<void>("/api/vault/activity", { method: "POST" }),
  createRule: (pattern: string, categoryId: number) => request<{ id: number }>("/api/rules", json({ pattern, categoryId })),
  deleteRule: (id: number) => request<void>(`/api/rules/${id}`, { method: "DELETE" }),
  categorize: (transactionId: number, categoryId: number | null, rulePattern?: string) =>
    request<void>(`/api/transactions/${transactionId}/category`, {
      ...json({ categoryId, rulePattern: rulePattern || null }),
      method: "PUT",
    }),
  portfolio: () => request<NetWorth>("/api/portfolio"),
  refreshPrices: () => request<PriceReport>("/api/portfolio/prices/refresh", { method: "POST" }),
  addValuation: (accountId: number, date: string, valueCents: number) =>
    request<void>(`/api/portfolio/${accountId}/valuations`, json({ date, valueCents })),
  recurring: () => request<RecurringOverview>("/api/recurring"),
  ignoreSeries: (key: string, ignored: boolean) =>
    request<void>("/api/recurring/ignored", { ...json({ key, ignored }), method: "PUT" }),
  projection: (month: string) => request<MonthProjection>(`/api/projection?month=${month}`),
  splitTransaction: (id: number, parts: { categoryId: number; amountCents: number }[]) =>
    request<void>(`/api/transactions/${id}/splits`, { ...json({ parts }), method: "PUT" }),
  setNote: (id: number, note: string | null) => request<void>(`/api/transactions/${id}/note`, { ...json({ note }), method: "PUT" }),
  summary: (month: string) => request<MonthSummary>(`/api/summary?month=${month}`),
  history: (month: string, months = 12) => request<SummaryHistory>(`/api/summary/history?month=${month}&months=${months}`),
};
