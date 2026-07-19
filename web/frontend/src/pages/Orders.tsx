import { useEffect, useState } from 'react';
import { Check, History, Minus, Plus, ShoppingCart, X } from 'lucide-react';
import Badge from '../components/Badge';
import Button from '../components/Button';
import ProgressBar from '../components/ProgressBar';
import { getJSON, postJSON, putJSON } from '../api';
import type { Order } from '../types';

// 테이블 컬럼 폭 (design-spec §5.2)
const COL = {
  check: 52,
  remain: 170,
  threshold: 100,
  qty: 150,
  price: 130,
};

function parsePct(qty: string | null): number {
  const n = parseInt(qty ?? '', 10);
  return Number.isNaN(n) ? 0 : n;
}

function won(amount: number): string {
  return `₩${amount.toLocaleString('ko-KR')}`;
}

export default function Orders() {
  const [orders, setOrders] = useState<Order[]>([]);
  const [qty, setQty] = useState<Record<number, number>>({});
  const [showHistory, setShowHistory] = useState(false);
  const [loading, setLoading] = useState(true);
  const [notice, setNotice] = useState<{ ok: boolean; text: string } | null>(null);

  const fetchOrders = async () => {
    try {
      setOrders(await getJSON<Order[]>('/api/orders'));
    } catch {
      // 무시
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchOrders();
  }, []);

  const qtyOf = (id: number) => qty[id] ?? 1;
  const changeQty = (id: number, delta: number) =>
    setQty((prev) => ({ ...prev, [id]: Math.max(1, qtyOf(id) + delta) }));

  const pending = orders.filter((o) => o.status === 'pending');
  const ordered = orders.filter((o) => o.status === 'ordered');
  const list = showHistory ? ordered : pending;
  const selectedOrders = pending.filter((o) => o.selected);
  const totalAmount = selectedOrders.reduce((sum, o) => sum + o.price * qtyOf(o.id), 0);

  const flash = (ok: boolean, text: string) => {
    setNotice({ ok, text });
    setTimeout(() => setNotice(null), 4000);
  };

  const toggleSelect = async (order: Order) => {
    const next = !order.selected;
    setOrders((prev) => prev.map((o) => (o.id === order.id ? { ...o, selected: next } : o)));
    try {
      await putJSON(`/api/orders/${order.id}`, { selected: next });
    } catch {
      // 실패 시 롤백
      setOrders((prev) =>
        prev.map((o) => (o.id === order.id ? { ...o, selected: order.selected } : o)),
      );
    }
  };

  const excludeSelected = async () => {
    try {
      await Promise.all(
        selectedOrders.map((o) => putJSON(`/api/orders/${o.id}`, { selected: false })),
      );
      await fetchOrders();
    } catch (err) {
      flash(false, err instanceof Error ? err.message : '후보 제외에 실패했습니다.');
    }
  };

  const confirmOrders = async () => {
    try {
      const res = await postJSON<{ status: string; count: number }>('/api/orders/confirm');
      flash(true, `${res.count}건의 주문이 확정되었습니다.`);
      await fetchOrders();
    } catch (err) {
      flash(false, err instanceof Error ? err.message : '주문 컨펌에 실패했습니다.');
    }
  };

  return (
    <div className="page">
      <div className="topbar">
        <div className="topbar-info">
          <h1 className="topbar-title">주문 관리</h1>
          <span className="topbar-sub">
            {showHistory
              ? `주문 완료된 시약 ${ordered.length}건`
              : `재고 부족으로 주문 후보에 등록된 시약 ${pending.length}건`}
          </span>
        </div>
        <Button variant="outline" icon={History} onClick={() => setShowHistory(!showHistory)}>
          {showHistory ? '주문 후보' : '주문 내역'}
        </Button>
      </div>

      <div className="card table-card">
        <div className="t-head">
          <div className="t-cell" style={{ width: COL.check }}>
            <span className="checkbox" />
          </div>
          <div className="t-cell grow">시약 이름</div>
          <div className="t-cell" style={{ width: COL.remain }}>현재 잔량</div>
          <div className="t-cell" style={{ width: COL.threshold }}>기준 수량</div>
          <div className="t-cell" style={{ width: COL.qty }}>주문 수량</div>
          <div className="t-cell" style={{ width: COL.price }}>예상 금액</div>
        </div>
        <div className="t-body">
          {loading ? (
            <div className="t-empty">데이터를 불러오는 중입니다...</div>
          ) : list.length === 0 ? (
            <div className="t-empty">
              {showHistory ? '주문 내역이 없습니다.' : '주문 후보가 없습니다.'}
            </div>
          ) : (
            list.map((order) => {
              const checked = !showHistory && order.selected;
              return (
                <div key={order.id} className={`t-row${checked ? ' checked' : ''}`}>
                  <div className="t-cell" style={{ width: COL.check, padding: '14px 16px' }}>
                    {showHistory ? (
                      <Badge variant="primary">완료</Badge>
                    ) : (
                      <button
                        className={`checkbox${order.selected ? ' checked' : ''}`}
                        onClick={() => toggleSelect(order)}
                        title={order.selected ? '선택 해제' : '선택'}
                      >
                        {order.selected && <Check size={11} />}
                      </button>
                    )}
                  </div>
                  <div className="t-cell grow" style={{ padding: '12px 16px' }}>
                    <div className="cell-title">
                      <span className="cell-title-main">{order.chemical_name}</span>
                      <span className="cell-title-sub">
                        {[order.formula, order.manufacturer].filter(Boolean).join(' · ')}
                      </span>
                    </div>
                  </div>
                  <div className="t-cell" style={{ width: COL.remain, padding: '12px 16px' }}>
                    <ProgressBar percent={parsePct(order.current_qty)} trackWidth={64} danger />
                  </div>
                  <div className="t-cell" style={{ width: COL.threshold, padding: '12px 16px' }}>
                    {order.threshold_qty ?? '-'}
                  </div>
                  <div className="t-cell" style={{ width: COL.qty, padding: '12px 16px' }}>
                    {showHistory ? (
                      <span>{qtyOf(order.id)}</span>
                    ) : (
                      <div className="stepper">
                        <button className="stepper-btn" onClick={() => changeQty(order.id, -1)}>
                          <Minus size={13} />
                        </button>
                        <span className="stepper-count">{qtyOf(order.id)}</span>
                        <button className="stepper-btn plus" onClick={() => changeQty(order.id, 1)}>
                          <Plus size={13} />
                        </button>
                      </div>
                    )}
                  </div>
                  <div
                    className="t-cell"
                    style={{
                      width: COL.price,
                      padding: '12px 16px',
                      fontWeight: 600,
                      color: 'var(--text-strong)',
                    }}
                  >
                    {won(order.price * qtyOf(order.id))}
                  </div>
                </div>
              );
            })
          )}
        </div>
      </div>

      {notice && <div className={notice.ok ? 'form-success' : 'form-error'}>{notice.text}</div>}

      {!showHistory && (
        <div className="footer-bar">
          <div className="footer-bar-summary">
            <ShoppingCart size={18} />
            {selectedOrders.length}건 선택 · 예상 합계 {won(totalAmount)}
          </div>
          <div className="footer-bar-actions">
            <Button
              variant="outline"
              icon={X}
              onClick={excludeSelected}
              disabled={selectedOrders.length === 0}
            >
              후보에서 제외
            </Button>
            <Button icon={Check} onClick={confirmOrders} disabled={selectedOrders.length === 0}>
              선택 항목 주문 컨펌
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}
