import React, { useState, useEffect } from 'react';
import { ShoppingBag, CheckCircle, Check, DollarSign } from 'lucide-react';

interface OrderItem {
  id: number;
  chemical_name: string;
  formula: string | null;
  manufacturer: string | null;
  current_qty: string;
  threshold_qty: string;
  price: number;
  selected: boolean;
  status: string;
}

export default function Orders() {
  const [orders, setOrders] = useState<OrderItem[]>([]);
  const [loading, setLoading] = useState(true);

  const fetchOrders = async () => {
    try {
      const response = await fetch('/api/orders');
      if (response.ok) {
        const data = await response.json();
        setOrders(data);
      }
    } catch (err) {
      console.error(err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchOrders();
  }, []);

  const toggleSelect = async (id: number, currentSelected: boolean) => {
    try {
      const response = await fetch(`/api/orders/${id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ selected: !currentSelected })
      });
      if (response.ok) {
        // optimistically update state
        setOrders(prev => prev.map(item => 
          item.id === id ? { ...item, selected: !currentSelected } : item
        ));
      }
    } catch (err) {
      console.error(err);
    }
  };

  const handleConfirmOrder = async () => {
    const selectedCount = orders.filter(o => o.selected && o.status === 'pending').length;
    if (selectedCount === 0) {
      alert('주문할 항목을 선택해주세요.');
      return;
    }

    try {
      const response = await fetch('/api/orders/confirm', {
        method: 'POST'
      });
      if (response.ok) {
        alert('선택된 항목들의 주문 컨펌이 성공적으로 완료되었습니다.');
        fetchOrders();
      } else {
        alert('주문 컨펌 실패');
      }
    } catch (err) {
      alert('서버 통신 오류');
    }
  };

  const pendingOrders = orders.filter(o => o.status === 'pending');
  const completedOrders = orders.filter(o => o.status === 'ordered');
  
  const selectedItems = pendingOrders.filter(o => o.selected);
  const totalCost = selectedItems.reduce((sum, item) => sum + item.price, 0);

  return (
    <div className="main-content">
      <header className="topbar">
        <div className="page-title">
          <h2>주문 관리</h2>
          <p>재고 부족으로 자동 감지되거나 보완이 필요한 시약들을 발주하고 주문 현황을 파악합니다.</p>
        </div>
      </header>

      <div className="content-body" style={{ gap: '32px' }}>
        <div className="card table-card" style={{ flexGrow: 2 }}>
          <div className="card-title">
            <span>주문 후보 목록 ({pendingOrders.length}건)</span>
            <span style={{ fontSize: '13px', color: 'var(--text-muted)' }}>잔량이 기준치 이하인 시약이 자동으로 등록됩니다</span>
          </div>

          <div className="table-wrapper">
            {loading ? (
              <p style={{ padding: '20px', textAlign: 'center' }}>데이터를 불러오는 중입니다...</p>
            ) : pendingOrders.length === 0 ? (
              <p style={{ padding: '40px', textAlign: 'center', color: 'var(--text-muted)' }}>대기 중인 주문 후보가 없습니다.</p>
            ) : (
              <table>
                <thead>
                  <tr>
                    <th style={{ width: '40px' }}>선택</th>
                    <th>시약 정보</th>
                    <th>현재 잔량</th>
                    <th>기준치</th>
                    <th>단가</th>
                  </tr>
                </thead>
                <tbody>
                  {pendingOrders.map(item => (
                    <tr 
                      key={item.id}
                      onClick={() => toggleSelect(item.id, item.selected)}
                      className={item.selected ? 'selected' : ''}
                      style={{ cursor: 'pointer' }}
                    >
                      <td onClick={(e) => e.stopPropagation()}>
                        <input 
                          type="checkbox" 
                          checked={item.selected}
                          onChange={() => toggleSelect(item.id, item.selected)}
                          style={{ accentColor: 'var(--primary)', width: '16px', height: '16px' }}
                        />
                      </td>
                      <td>
                        <div style={{ display: 'flex', flexDirection: 'column' }}>
                          <span style={{ fontWeight: 700, color: 'var(--text-strong)' }}>{item.chemical_name}</span>
                          <span style={{ fontSize: '11px', color: 'var(--text-muted)', marginTop: '2px' }}>
                            {item.formula} · {item.manufacturer}
                          </span>
                        </div>
                      </td>
                      <td>
                        <span className="badge badge-danger">{item.current_qty}</span>
                      </td>
                      <td>{item.threshold_qty}</td>
                      <td style={{ fontWeight: 700, color: 'var(--text-strong)' }}>
                        ₩{item.price.toLocaleString()}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        </div>

        {/* Order Sidebar Summary */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: '24px', width: '360px', flexShrink: 0 }}>
          <div className="card" style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
            <h3 style={{ fontSize: '16px', fontWeight: 700, color: 'var(--text-strong)' }}>발주 요약</h3>
            
            <div style={{ borderTop: '1px solid var(--border)', borderBottom: '1px solid var(--border)', padding: '16px 0', display: 'flex', flexDirection: 'column', gap: '12px' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '14px' }}>
                <span style={{ color: 'var(--text-muted)' }}>선택한 품목</span>
                <strong style={{ color: 'var(--text-strong)' }}>{selectedItems.length} 건</strong>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '16px' }}>
                <span style={{ color: 'var(--text-muted)' }}>예상 총합</span>
                <strong style={{ color: 'var(--primary)', fontSize: '18px' }}>₩{totalCost.toLocaleString()}</strong>
              </div>
            </div>

            <button 
              className="btn btn-primary"
              style={{ width: '100%', padding: '12px' }}
              disabled={selectedItems.length === 0}
              onClick={handleConfirmOrder}
            >
              선택 항목 주문 컨펌
            </button>
          </div>

          {/* Completed Orders List */}
          <div className="card" style={{ flexGrow: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
            <h3 style={{ fontSize: '16px', fontWeight: 700, color: 'var(--text-strong)', marginBottom: '14px' }}>
              주문 완료 내역 ({completedOrders.length}건)
            </h3>
            <div style={{ overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: '10px' }}>
              {completedOrders.length === 0 ? (
                <p style={{ fontSize: '13px', color: 'var(--text-muted)', textAlign: 'center', padding: '24px 0' }}>주문 완료 내역이 없습니다.</p>
              ) : (
                completedOrders.map(item => (
                  <div key={item.id} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '12px', background: 'var(--bg)', borderRadius: 'var(--radius-sm)' }}>
                    <div>
                      <h4 style={{ fontSize: '13px', fontWeight: 700, color: 'var(--text-strong)' }}>{item.chemical_name}</h4>
                      <p style={{ fontSize: '11px', color: 'var(--text-muted)', marginTop: '2px' }}>{item.manufacturer}</p>
                    </div>
                    <span className="badge badge-success">
                      <Check size={12} /> 주문완료
                    </span>
                  </div>
                ))
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
