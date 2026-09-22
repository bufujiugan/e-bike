// Explicitly published, fictitious bank data for isolated API acceptance only.
export async function createPiDocument(request, prefix, values) {
  if (process.env.BIKE_ACCEPTANCE_WRITE !== 'isolated') throw new Error('仅允许专用验收库');
  const document = {
    sellerAddress: '隔离验收地址', sellerContact: '隔离验收联系人',
    bankName: 'TEST BANK - NOT FOR PAYMENT', bankAccount: '000-TEST-NOT-PAYABLE', beneficiary: '隔离验收收款单位',
    ...values, templateCode: prefix.toUpperCase() + '-PI', templateVersion: '1',
  };
  await request('/api/pi-templates', {
    templateCode: document.templateCode, templateVersion: document.templateVersion,
    tradeType: 'DOMESTIC', layoutCode: 'DOMESTIC_V1', sellerName: document.sellerName,
    sellerAddress: document.sellerAddress, sellerContact: document.sellerContact,
    bankName: document.bankName, bankAccount: document.bankAccount, beneficiary: document.beneficiary,
    reason: '隔离验收虚构资料，不得实际付款',
  });
  return document;
}
