import React, { useState } from 'react';
import { Bell, AlertTriangle, AlertCircle, ShieldAlert } from 'lucide-react';

interface TopBarProps {
  title: string;
  subtitle: string;
  alerts: {
    unscanned_checkouts: any[];
    expired_chemicals: any[];
    co_storage_warnings: any[];
  };
}

export default function TopBar({ title, subtitle, alerts }: TopBarProps) {
  const [showDropdown, setShowDropdown] = useState(false);
  
  const unscannedCount = alerts.unscanned_checkouts.length;
  const expiredCount = alerts.expired_chemicals.length;
  const warningCount = alerts.co_storage_warnings.length;
  const totalCount = unscannedCount + expiredCount + warningCount;

  return (
    <header className="topbar">
      <div className="page-title">
        <h2>{title}</h2>
        <p>{subtitle}</p>
      </div>

      <div className="topbar-actions">
        <div style={{ position: 'relative' }}>
          <button 
            className="btn btn-outline" 
            style={{ borderRadius: '50%', width: '44px', height: '44px', padding: 0 }}
            onClick={() => setShowDropdown(!showDropdown)}
          >
            <Bell size={20} />
            {totalCount > 0 && (
              <span style={{
                position: 'absolute',
                top: '6px',
                right: '6px',
                width: '10px',
                height: '10px',
                backgroundColor: 'var(--danger)',
                borderRadius: '50%',
                border: '2px solid var(--surface)'
              }} />
            )}
          </button>

          {showDropdown && (
            <div className="card" style={{
              position: 'absolute',
              right: 0,
              top: '54px',
              width: '380px',
              zIndex: 100,
              boxShadow: 'var(--shadow-lg)',
              maxHeight: '480px',
              overflowY: 'auto',
              padding: '16px'
            }}>
              <h4 style={{ color: 'var(--text-strong)', borderBottom: '1px solid var(--border)', paddingBottom: '10px', marginBottom: '10px' }}>
                경고 및 예외 알림 ({totalCount}건)
              </h4>
              
              {totalCount === 0 ? (
                <p style={{ fontSize: '13px', color: 'var(--text-muted)', textAlign: 'center', padding: '16px 0' }}>
                  현재 탐지된 경고 상황이 없습니다.
                </p>
              ) : (
                <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
                  {/* Unscanned Checkouts */}
                  {alerts.unscanned_checkouts.map((item, idx) => (
                    <div key={`un-${idx}`} style={{ display: 'flex', gap: '10px', backgroundColor: 'var(--danger-soft)', padding: '12px', borderRadius: 'var(--radius-sm)' }}>
                      <AlertCircle size={18} color="var(--danger)" style={{ flexShrink: 0, marginTop: '2px' }} />
                      <div>
                        <h5 style={{ fontSize: '13px', fontWeight: 700, color: 'var(--danger)' }}>반출 스캔 미완료</h5>
                        <p style={{ fontSize: '12px', color: 'var(--text-body)', marginTop: '2px' }}>
                          선반에서 <strong>{item.chemical_name}</strong>(이)가 회수되었으나 반출 스캔이 완료되지 않았습니다. ({item.shelf_desc})
                        </p>
                      </div>
                    </div>
                  ))}

                  {/* Co-storage Warnings */}
                  {alerts.co_storage_warnings.map((item, idx) => (
                    <div key={`co-${idx}`} style={{ display: 'flex', gap: '10px', backgroundColor: 'var(--warning-soft)', padding: '12px', borderRadius: 'var(--radius-sm)' }}>
                      <ShieldAlert size={18} color="var(--warning)" style={{ flexShrink: 0, marginTop: '2px' }} />
                      <div>
                        <h5 style={{ fontSize: '13px', fontWeight: 700, color: 'var(--warning)' }}>인접 보관 주의 (화학 반응 위험)</h5>
                        <p style={{ fontSize: '12px', color: 'var(--text-body)', marginTop: '2px' }}>
                          {item.message}
                        </p>
                      </div>
                    </div>
                  ))}

                  {/* Expired Chemicals */}
                  {alerts.expired_chemicals.map((item, idx) => (
                    <div key={`ex-${idx}`} style={{ display: 'flex', gap: '10px', backgroundColor: 'var(--warning-soft)', padding: '12px', borderRadius: 'var(--radius-sm)', border: item.days_over > 0 ? '1px solid var(--danger)' : 'none' }}>
                      <AlertTriangle size={18} color={item.days_over > 0 ? 'var(--danger)' : 'var(--warning)'} style={{ flexShrink: 0, marginTop: '2px' }} />
                      <div>
                        <h5 style={{ fontSize: '13px', fontWeight: 700, color: item.days_over > 0 ? 'var(--danger)' : 'var(--warning)' }}>
                          {item.days_over > 0 ? '유통기한 초과' : '유통기한 임박'}
                        </h5>
                        <p style={{ fontSize: '12px', color: 'var(--text-body)', marginTop: '2px' }}>
                          <strong>{item.chemical_name}</strong>의 유통기한({item.expiration_date})이 {item.days_over > 0 ? `${item.days_over}일 경과` : '30일 이내로 임박'}했습니다.
                        </p>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}
        </div>
      </div>
    </header>
  );
}
