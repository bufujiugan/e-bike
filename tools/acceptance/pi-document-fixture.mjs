// Explicitly published, fictitious bank data for isolated API acceptance only.
export async function createPiDocument(request, prefix, values) {
  if (process.env.BIKE_ACCEPTANCE_WRITE !== 'isolated') throw new Error('仅允许专用验收库');
  const document = {
    issueDate: '2026-09-23', validUntil: '2026-10-23', buyerAddress: '隔离买方地址', buyerContact: '隔离买方联系人',
    shippingAddress: '隔离收货地址', transportMode: '测试自提', sellerCountry: 'CN', buyerCountry: 'CN',
    sellerAddress: '隔离验收地址', sellerContact: '隔离验收联系人',
    sellerContactName: '隔离卖方联系人', sellerPhone: '+86-000-TEST-ONLY', sellerEmail: 'seller@example.invalid',
    bankName: 'TEST BANK - NOT FOR PAYMENT', bankAccount: '000-TEST-NOT-PAYABLE', beneficiary: '隔离验收收款单位',
    ...values, templateCode: prefix.toUpperCase() + '-PI', templateVersion: '1',
  };
  await request('/api/pi-templates', {
    templateCode: document.templateCode, templateVersion: document.templateVersion,
    tradeType: 'DOMESTIC', layoutCode: 'DOMESTIC_V3', sellerName: document.sellerName,
    sellerAddress: document.sellerAddress, sellerContact: document.sellerContact,
    sellerCountry: document.sellerCountry, sellerContactName: document.sellerContactName, sellerPhone: document.sellerPhone, sellerEmail: document.sellerEmail,
    bankName: document.bankName, bankAccount: document.bankAccount, beneficiary: document.beneficiary,
    reason: '隔离验收虚构资料，不得实际付款',
  });
  return document;
}
