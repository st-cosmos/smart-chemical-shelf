import React, { useState, useEffect } from 'react';
import { Plus, Settings, Battery, Lightbulb, User, Check, Layers, AlertCircle } from 'lucide-react';

interface ShelfDevice {
  id: string;
  name: string | null;
  parent_shelf: string | null;
  row: number | null;
  col: number | null;
  weight: number;
  prev_weight: number;
  battery: number;
  status: string;
  led_on: boolean;
  led_message: string;
  updated_time: string;
}

interface ShelfConfig {
  id: string;
  name: string;
  rows: number;
  cols: number;
}

export default function Shelves() {
  const [shelves, setShelves] = useState<ShelfDevice[]>([]);
  const [configs, setConfigs] = useState<ShelfConfig[]>([]);
  const [loading, setLoading] = useState(true);
  
  // Registration state
  const [registeringCell, setRegisteringCell] = useState<{ shelfId: string; row: number; col: number } | null>(null);
  const [selectedDeviceId, setSelectedDeviceId] = useState('');
  const [customName, setCustomName] = useState('');

  const fetchData = async () => {
    try {
      const shelfRes = await fetch('/api/shelves');
      const configRes = await fetch('/api/shelves/configs');
      if (shelfRes.ok && configRes.ok) {
        const shelfData = await shelfRes.json();
        const configData = await configRes.json();
        setShelves(shelfData);
        setConfigs(configData);
      }
    } catch (err) {
      console.error("Error fetching shelf data:", err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchData();
    const interval = setInterval(fetchData, 4000);
    return () => clearInterval(interval);
  }, []);

  const unregisteredDevices = shelves.filter(s => s.status === 'unregistered');

  const handleRegister = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!registeringCell || !selectedDeviceId || !customName) return;

    try {
      const response = await fetch(`/api/shelves/register/${selectedDeviceId}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: customName,
          parent_shelf: registeringCell.shelfId,
          row: registeringCell.row,
          col: registeringCell.col
        })
      });

      if (response.ok) {
        setRegisteringCell(null);
        setSelectedDeviceId('');
        setCustomName('');
        fetchData();
      } else {
        alert('등록 실패');
      }
    } catch (err) {
      alert('등록 중 오류 발생');
    }
  };

  const handleAddRow = async (shelfId: string, currentRows: number) => {
    try {
      const response = await fetch(`/api/shelves/configs/${shelfId}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ rows: currentRows + 1 })
      });
      if (response.ok) {
        fetchData();
      }
    } catch (err) {
      console.error(err);
    }
  };

  const handleAddCol = async (shelfId: string, currentCol: number) => {
    try {
      const response = await fetch(`/api/shelves/configs/${shelfId}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ cols: currentCol + 1 })
      });
      if (response.ok) {
        fetchData();
      }
    } catch (err) {
      console.error(err);
    }
  };

  const toggleLed = async (deviceId: string, currentLedOn: boolean) => {
    try {
      const response = await fetch(`/api/shelves/${deviceId}/led`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          led_on: !currentLedOn,
          led_message: !currentLedOn ? 'Web Manual Toggle' : ''
        })
      });
      if (response.ok) {
        fetchData();
      }
    } catch (err) {
      console.error(err);
    }
  };

  return (
    <div className="main-content">
      <header className="topbar">
        <div className="page-title">
          <h2>선반 관리</h2>
          <p>스마트 쉘프의 구역별 선반 상태 및 적재 현황을 실시간으로 모니터링합니다.</p>
        </div>
      </header>

      <div className="content-body" style={{ flexDirection: 'column' }}>
        {/* Unregistered Devices Banner */}
        {unregisteredDevices.length > 0 && (
          <div className="card" style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            backgroundColor: 'var(--primary-soft)',
            borderColor: 'var(--primary)',
            borderWidth: '1.5px',
            marginBottom: '10px'
          }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '16px' }}>
              <div style={{
                width: '48px',
                height: '48px',
                borderRadius: '50%',
                backgroundColor: 'var(--primary)',
                color: 'white',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center'
              }}>
                <Layers size={24} />
              </div>
              <div>
                <h3 style={{ fontSize: '16px', fontWeight: 700, color: 'var(--primary)' }}>신규 선반 기기 감지됨</h3>
                <p style={{ fontSize: '13px', color: 'var(--text-body)', marginTop: '2px' }}>
                  현재 {unregisteredDevices.length}대의 미등록 기기가 네트워크에 잡혔습니다. 아래 선반 그리드의 <strong>[빈 슬롯]</strong>을 클릭하여 위치를 배정하세요.
                </p>
              </div>
            </div>
            <div style={{ display: 'flex', gap: '8px' }}>
              {unregisteredDevices.map(d => (
                <span key={d.id} className="badge badge-primary" style={{ padding: '8px 14px', fontSize: '13px', fontWeight: 700 }}>
                  🔋 {d.id} (배터리 {d.battery}%)
                </span>
              ))}
            </div>
          </div>
        )}

        <div className="shelves-area">
          {configs.map(config => {
            return (
              <div key={config.id} className="card shelf-card">
                <div className="shelf-title-bar">
                  <div>
                    <h3 style={{ fontSize: '18px', fontWeight: 700, color: 'var(--text-strong)' }}>선반 {config.id}</h3>
                    <span className="shelf-info-specs">{config.rows}행 × {config.cols}열 그리드</span>
                  </div>
                  <div style={{ display: 'flex', gap: '8px' }}>
                    <button 
                      className="btn btn-outline" 
                      onClick={() => handleAddRow(config.id, config.rows)}
                      style={{ padding: '6px 12px', fontSize: '12px' }}
                    >
                      <Plus size={14} /> 행 추가
                    </button>
                    <button 
                      className="btn btn-outline" 
                      onClick={() => handleAddCol(config.id, config.cols)}
                      style={{ padding: '6px 12px', fontSize: '12px' }}
                    >
                      <Plus size={14} /> 열 추가
                    </button>
                  </div>
                </div>

                <div 
                  className="shelf-grid"
                  style={{
                    gridTemplateColumns: `repeat(${config.cols}, 1fr)`
                  }}
                >
                  {Array.from({ length: config.rows }).map((_, rIdx) => {
                    const r = rIdx + 1;
                    return Array.from({ length: config.cols }).map((_, cIdx) => {
                      const c = cIdx + 1;
                      // Find registered device
                      const device = shelves.find(s => 
                        s.parent_shelf === config.id && 
                        s.row === r && 
                        s.col === c && 
                        s.status === 'registered'
                      );

                      if (device) {
                        return (
                          <div 
                            key={`${config.id}-${r}-${c}`}
                            className={`grid-cell occupied ${device.led_on ? 'led-on' : ''}`}
                          >
                            <div className="cell-header">
                              <span>{device.name || `${config.id}${r}-${c}`}</span>
                              <span style={{ display: 'flex', alignItems: 'center', gap: '2px' }}>
                                <Battery size={12} color="var(--text-muted)" /> {device.battery}%
                              </span>
                            </div>
                            <div className="cell-body">
                              {device.id}
                            </div>
                            <div className="cell-footer">
                              <span className="cell-weight">{device.weight.toFixed(1)} kg</span>
                              <button 
                                onClick={() => toggleLed(device.id, device.led_on)}
                                style={{
                                  border: 'none',
                                  background: 'transparent',
                                  cursor: 'pointer',
                                  color: device.led_on ? 'var(--primary)' : 'var(--text-muted)'
                                }}
                                title={device.led_on ? "LED 끄기" : "LED 켜기"}
                              >
                                <Lightbulb size={16} />
                              </button>
                            </div>
                          </div>
                        );
                      } else {
                        return (
                          <div 
                            key={`${config.id}-${r}-${c}`}
                            className="grid-cell empty"
                            onClick={() => {
                              if (unregisteredDevices.length === 0) {
                                alert('배치 가능한 미등록 기기가 없습니다.');
                                return;
                              }
                              setRegisteringCell({ shelfId: config.id, row: r, col: c });
                              setSelectedDeviceId(unregisteredDevices[0].id);
                              setCustomName(`수납칸 ${config.id}${r * config.cols - (config.cols - c)}`);
                            }}
                          >
                            <Plus size={18} />
                            <span>빈 슬롯</span>
                          </div>
                        );
                      }
                    });
                  })}
                </div>
              </div>
            );
          })}
        </div>
      </div>

      {/* Registration Modal Overlay */}
      {registeringCell && (
        <div style={{
          position: 'fixed',
          top: 0,
          left: 0,
          right: 0,
          bottom: 0,
          backgroundColor: 'var(--overlay)',
          zIndex: 999,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center'
        }}>
          <div className="card" style={{ width: '420px', padding: '32px', boxShadow: 'var(--shadow-lg)', border: 'none' }}>
            <h3 style={{ fontSize: '18px', fontWeight: 700, color: 'var(--text-strong)', marginBottom: '18px' }}>
              기기 등록 — 선반 {registeringCell.shelfId}
            </h3>
            
            <form onSubmit={handleRegister}>
              <div className="form-group">
                <label>배치 위치</label>
                <input 
                  type="text" 
                  className="form-control" 
                  value={`선반 ${registeringCell.shelfId} · ${registeringCell.row}행 ${registeringCell.col}열`}
                  disabled
                />
              </div>

              <div className="form-group">
                <label>미등록 디바이스 ID</label>
                <select 
                  className="form-control"
                  value={selectedDeviceId}
                  onChange={(e) => setSelectedDeviceId(e.target.value)}
                >
                  {unregisteredDevices.map(d => (
                    <option key={d.id} value={d.id}>{d.id} (배터리 {d.battery}%)</option>
                  ))}
                </select>
              </div>

              <div className="form-group">
                <label>기기 식별 이름</label>
                <input 
                  type="text" 
                  className="form-control" 
                  placeholder="예: 수납칸 A7"
                  value={customName}
                  onChange={(e) => setCustomName(e.target.value)}
                />
              </div>

              <div style={{ display: 'flex', gap: '12px', marginTop: '28px' }}>
                <button 
                  type="button" 
                  className="btn btn-outline" 
                  style={{ flexGrow: 1 }}
                  onClick={() => setRegisteringCell(null)}
                >
                  취소
                </button>
                <button 
                  type="submit" 
                  className="btn btn-primary" 
                  style={{ flexGrow: 1 }}
                >
                  등록
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
