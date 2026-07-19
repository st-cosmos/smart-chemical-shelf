import React, { useState, useEffect } from 'react';
import { Search, AlertTriangle, AlertCircle, Eye, EyeOff, ShieldAlert, Sparkles } from 'lucide-react';

interface Chemical {
  id: string;
  name: string;
  cas_no: string | null;
  formula: string | null;
  weight: number;
  shelf_id: string | null;
  shelf_row: number | null;
  shelf_col: number | null;
  current_status: string;
  holder_username: string | null;
  time_in: string | null;
  time_out: string | null;
  expiration_date: string | null;
  manufacturer: string | null;
}

interface TransactionLog {
  id: number;
  chemical_id: string;
  chemical_name: string;
  action: string;
  operator_name: string;
  details: string | null;
  timestamp: string;
}

interface InventoryProps {
  alerts: {
    unscanned_checkouts: any[];
    expired_chemicals: any[];
    co_storage_warnings: any[];
  };
  refreshAlerts: () => void;
}

export default function Inventory({ alerts, refreshAlerts }: InventoryProps) {
  const [chemicals, setChemicals] = useState<Chemical[]>([]);
  const [logs, setLogs] = useState<TransactionLog[]>([]);
  const [search, setSearch] = useState('');
  const [selectedChemId, setSelectedChemId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [ledStatus, setLedStatus] = useState<{ [key: string]: boolean }>({});

  const fetchInventory = async () => {
    try {
      const chemRes = await fetch('/api/chemicals');
      const logRes = await fetch('/api/logs');
      if (chemRes.ok && logRes.ok) {
        const chemData = await chemRes.json();
        const logData = await logRes.json();
        setChemicals(chemData);
        setLogs(logData);
      }
    } catch (err) {
      console.error("Failed to fetch inventory:", err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchInventory();
    const interval = setInterval(() => {
      fetchInventory();
      refreshAlerts();
    }, 4000); // refresh every 4s
    return () => clearInterval(interval);
  }, []);

  const triggerLed = async (chemId: string) => {
    try {
      const response = await fetch('/api/chemicals/select-led', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ chem_id: chemId })
      });
      if (response.ok) {
        setLedStatus(prev => ({ ...prev, [chemId]: true }));
        setTimeout(() => {
          setLedStatus(prev => ({ ...prev, [chemId]: false }));
        }, 15000); // visual indicator off after 15s (matching physical LED)
        alert('해당 시약 수납칸의 LED 가 점등되었습니다.');
      } else {
        const err = await response.json();
        alert(`LED 점등 실패: ${err.detail || '반입된 상태의 시약만 LED를 켤 수 있습니다.'}`);
      }
    } catch (err) {
      alert('LED 명령 전송 오류');
    }
  };

  // Helper check logic for icons in table
  const getWarningSymbol = (chem: Chemical) => {
    // 1. Expired check
    const expiredAlert = alerts.expired_chemicals.find(ex => ex.chemical_id === chem.id);
    // 2. Co-storage check
    const coAlert = alerts.co_storage_warnings.find(co => co.chemical_1_id === chem.id || co.chemical_2_id === chem.id);
    
    if (expiredAlert) {
      return (
        <span className={expiredAlert.days_over > 0 ? "badge badge-danger" : "badge badge-warning"} title={expiredAlert.days_over > 0 ? "유통기한 경과" : "유통기한 임박"} style={{ marginRight: '6px' }}>
          <AlertTriangle size={12} />
          {expiredAlert.days_over > 0 ? "기한 경과" : "기한 임박"}
        </span>
      );
    }
    if (coAlert) {
      return (
        <span className="badge badge-warning" title={coAlert.message} style={{ marginRight: '6px' }}>
          <ShieldAlert size={12} />
          인접 보관 주의
        </span>
      );
    }
    return null;
  };

  const filteredChemicals = chemicals.filter(c => 
    c.name.toLowerCase().includes(search.toLowerCase()) || 
    (c.formula && c.formula.toLowerCase().includes(search.toLowerCase())) ||
    (c.cas_no && c.cas_no.includes(search))
  );

  const selectedChem = chemicals.find(c => c.id === selectedChemId);
  const chemLogs = logs.filter(l => l.chemical_id === selectedChemId);

  return (
    <div style={{ display: 'flex', width: '100%', height: '100%', overflow: 'hidden' }}>
      <div className="main-content" style={{ flexGrow: 1, minWidth: 0 }}>
        <header className="topbar">
          <div className="page-title">
            <h2>재고 관리</h2>
            <p>보유중인 시약 리스트 시각화 ({filteredChemicals.length}종)</p>
          </div>
          <div className="topbar-actions">
            <div className="search-container">
              <Search className="search-icon" size={18} />
              <input 
                type="text" 
                className="search-input" 
                placeholder="시약 이름 · 식 · CAS 번호 검색"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
              />
            </div>
          </div>
        </header>

        <div className="content-body" style={{ padding: '24px' }}>
          <div className="card table-card" style={{ width: '100%' }}>
            <div className="table-wrapper">
              {loading ? (
                <p style={{ padding: '20px', textAlign: 'center' }}>데이터를 불러오는 중입니다...</p>
              ) : (
                <table>
                  <thead>
                    <tr>
                      <th>시약 이름</th>
                      <th>무게 (kg)</th>
                      <th>위치</th>
                      <th>잔량 (%)</th>
                      <th>유통기한</th>
                      <th>현재 상태</th>
                      <th>위치 안내</th>
                    </tr>
                  </thead>
                  <tbody>
                    {filteredChemicals.map(chem => (
                      <tr 
                        key={chem.id} 
                        onClick={() => setSelectedChemId(chem.id)}
                        className={selectedChemId === chem.id ? 'selected' : ''}
                        style={{ cursor: 'pointer' }}
                      >
                        <td>
                          <div style={{ display: 'flex', flexDirection: 'column' }}>
                            <span style={{ fontWeight: 700, color: 'var(--text-strong)', display: 'flex', alignItems: 'center', gap: '6px' }}>
                              {chem.name}
                              {getWarningSymbol(chem)}
                            </span>
                            <span style={{ fontSize: '11px', color: 'var(--text-muted)', marginTop: '2px' }}>
                              {chem.formula && `${chem.formula} · `}CAS {chem.cas_no || '-'}
                            </span>
                          </div>
                        </td>
                        <td>{chem.weight.toFixed(1)} kg</td>
                        <td style={{ fontWeight: 600 }}>
                          {chem.shelf_id ? `선반 ${chem.shelf_id.replace('SHELF-', '').substring(0, 1)} · ${chem.shelf_row}행 ${chem.shelf_col}열` : '-'}
                        </td>
                        <td>
                          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                            <div style={{ width: '60px', height: '6px', backgroundColor: 'var(--bg-deep)', borderRadius: '3px', overflow: 'hidden' }}>
                              <div style={{
                                width: chem.current_status === '반출중' ? '0%' : `${chem.weight * 100 / 2.0 > 100 ? 100 : chem.weight * 100 / 2.0}%`, // mock capacity based on 2kg limit
                                height: '100%',
                                backgroundColor: chem.weight < 0.4 ? 'var(--danger)' : 'var(--success)'
                              }} />
                            </div>
                            <span>{chem.current_status === '반출중' ? '0%' : `${Math.round(chem.weight * 100 / 2.0 > 100 ? 100 : chem.weight * 100 / 2.0)}%`}</span>
                          </div>
                        </td>
                        <td>{chem.expiration_date}</td>
                        <td>
                          <span className={chem.current_status === '비치중' ? 'badge badge-success' : 'badge badge-danger'}>
                            {chem.current_status}
                            {chem.current_status === '반출중' && chem.holder_username && ` · ${chem.holder_username}`}
                          </span>
                        </td>
                        <td onClick={(e) => e.stopPropagation()}>
                          <button 
                            className={`btn ${ledStatus[chem.id] ? 'btn-soft' : 'btn-outline'}`}
                            onClick={() => triggerLed(chem.id)}
                            style={{ padding: '6px 12px', fontSize: '12px' }}
                            disabled={chem.current_status === '반출중'}
                          >
                            💡 LED 켜기
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          </div>
        </div>
      </div>

      {selectedChem && (
        <aside className="detail-panel">
          <div className="detail-header">
            <div className="detail-title">
              <h3>{selectedChem.name}</h3>
              <p>{selectedChem.formula} · CAS {selectedChem.cas_no}</p>
            </div>
            <button 
              className="btn btn-outline"
              style={{ padding: '6px', border: 'none', background: 'transparent' }}
              onClick={() => setSelectedChemId(null)}
            >
              ✕
            </button>
          </div>

          <div style={{ display: 'flex', gap: '8px' }}>
            <span className={selectedChem.current_status === '비치중' ? 'badge badge-success' : 'badge badge-danger'}>
              {selectedChem.current_status === '비치중' ? '비치중' : '반출중'}
            </span>
            {alerts.expired_chemicals.some(ex => ex.chemical_id === selectedChem.id) && (
              <span className="badge badge-danger">유통기한 경과</span>
            )}
            {alerts.co_storage_warnings.some(co => co.chemical_1_id === selectedChem.id || co.chemical_2_id === selectedChem.id) && (
              <span className="badge badge-warning">인접 보관 주의</span>
            )}
          </div>

          <div className="info-grid">
            <div className="info-row">
              <span className="info-label">무게</span>
              <span className="info-value">{selectedChem.weight.toFixed(2)} kg</span>
            </div>
            <div className="info-row">
              <span className="info-label">보관 위치</span>
              <span className="info-value">
                {selectedChem.shelf_id ? `선반 ${selectedChem.shelf_id.replace('SHELF-', '').substring(0, 1)} · ${selectedChem.shelf_row}행 ${selectedChem.shelf_col}열` : '-'}
              </span>
            </div>
            <div className="info-row">
              <span className="info-label">잔량</span>
              <span className="info-value">
                {selectedChem.current_status === '반출중' ? '0%' : `${Math.round(selectedChem.weight * 100 / 2.0)}% (${Math.round(selectedChem.weight * 1000)} mL)`}
              </span>
            </div>
            <div className="info-row">
              <span className="info-label">유통기한</span>
              <span className="info-value">{selectedChem.expiration_date}</span>
            </div>
            <div className="info-row">
              <span className="info-label">현재 상태</span>
              <span className="info-value">
                {selectedChem.current_status === '비치중' ? '보관중' : `반출중 · ${selectedChem.holder_username || ''}`}
              </span>
            </div>
          </div>

          <div className="log-section" style={{ display: 'flex', flexDirection: 'column', gap: '12px', flexGrow: 1, overflow: 'hidden' }}>
            <h4 style={{ color: 'var(--text-strong)', borderBottom: '1px solid var(--border)', paddingBottom: '8px' }}>반출입 로그</h4>
            <div className="log-list" style={{ overflowY: 'auto', flexGrow: 1, paddingRight: '4px' }}>
              {chemLogs.length === 0 ? (
                <p style={{ fontSize: '12px', color: 'var(--text-muted)', textAlign: 'center', padding: '16px 0' }}>로그 기록이 없습니다.</p>
              ) : (
                chemLogs.map(log => (
                  <div key={log.id} className={`log-item ${log.action}`}>
                    <div className="log-bullet" />
                    <div className="log-content">
                      <div className="log-text">
                        <strong>{log.action}</strong> · {log.operator_name}
                      </div>
                      <div className="log-time" style={{ fontSize: '11px', color: 'var(--text-muted)' }}>
                        {log.timestamp}
                      </div>
                    </div>
                  </div>
                ))
              )}
            </div>
          </div>
        </aside>
      )}
    </div>
  );
}
