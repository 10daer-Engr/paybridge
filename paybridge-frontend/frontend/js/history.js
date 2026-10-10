// PayBridge has no "list my transfers" endpoint, so this browser remembers the transfers it has
// seen (per customer) and re-asks the server for the current status whenever you open one.

const storeKey = (customerId) => `paybridge.transfers.${customerId}`;

export function listTransfers(customerId) {
  try { return JSON.parse(localStorage.getItem(storeKey(customerId)) || "[]"); } catch { return []; }
}

export function saveTransfer(customerId, t) {
  if (!customerId || !t || !t.id) return;
  const entry = {
    id: t.id, type: t.type, status: t.status, amount: t.amount, currency: t.currency,
    sourceAccountNumber: t.sourceAccountNumber, destinationAccountNumber: t.destinationAccountNumber,
    destinationBankCode: t.destinationBankCode, destinationAccountName: t.destinationAccountName,
    narration: t.narration, createdAt: t.createdAt,
  };
  const list = listTransfers(customerId).filter((x) => x.id !== t.id);
  list.unshift(entry);
  try { localStorage.setItem(storeKey(customerId), JSON.stringify(list.slice(0, 50))); } catch { /* ignore */ }
}
