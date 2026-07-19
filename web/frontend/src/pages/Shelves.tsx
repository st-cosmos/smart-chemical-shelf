import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { BatteryFull, CirclePlus, Lightbulb, Pencil, Plus, Weight } from 'lucide-react';
import Button from '../components/Button';
import Input from '../components/Input';
import { getJSON, postJSON } from '../api';
import type { ShelfConfig, ShelfDevice } from '../types';

interface RegisterTarget {
  shelfId: string;
  row: number;
  col: number;
}

export default function Shelves() {
  const [devices, setDevices] = useState<ShelfDevice[]>([]);
  const [configs, setConfigs] = useState<ShelfConfig[]>([]);
  const [loading, setLoading] = useState(true);

  const [target, setTarget] = useState<RegisterTarget | null>(null);
  const [deviceId, setDeviceId] = useState('');
  const [slotName, setSlotName] = useState('');
  const [modalError, setModalError] = useState('');

  const fetchData = async () => {
    try {
      const [devs, cfgs] = await Promise.all([
        getJSON<ShelfDevice[]>('/api/shelves'),
        getJSON<ShelfConfig[]>('/api/shelves/configs'),
      ]);
      setDevices(devs);
      setConfigs(cfgs);
    } catch {
      // 폴링 실패 무시
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchData();
    const timer = setInterval(fetchData, 5000);
    return () => clearInterval(timer);
  }, []);

  const unregistered = devices.filter((d) => d.status === 'unregistered');

  const updateConfig = async (id: string, body: { rows?: number; cols?: number }) => {
    try {
      await postJSON(`/api/shelves/configs/${id}`, body);
      await fetchData();
    } catch {
      // 무시
    }
  };

  const addShelf = async () => {
    const used = new Set(configs.map((c) => c.id));
    let code = 'A'.charCodeAt(0);
    while (used.has(String.fromCharCode(code))) code += 1;
    await updateConfig(String.fromCharCode(code), { rows: 3, cols: 3 });
  };

  const openRegister = (t: RegisterTarget) => {
    if (unregistered.length === 0) return;
    setTarget(t);
    setDeviceId(unregistered[0].id);
    setSlotName(`수납칸 ${t.shelfId}${(t.row - 1) * (configs.find((c) => c.id === t.shelfId)?.cols ?? 1) + t.col}`);
    setModalError('');
  };

  const handleRegister = async (e: FormEvent) => {
    e.preventDefault();
    if (!target || !deviceId || !slotName) return;
    try {
      await postJSON(`/api/shelves/register/${deviceId}`, {
        name: slotName,
        parent_shelf: target.shelfId,
        row: target.row,
        col: target.col,
      });
      setTarget(null);
      await fetchData();
    } catch (err) {
      setModalError(err instanceof Error ? err.message : '기기 등록에 실패했습니다.');
    }
  };

  return (
    <div className="page">
      <div className="topbar">
        <div className="topbar-info">
          <h1 className="topbar-title" style={{ fontSize: 24 }}>선반 관리</h1>
          <span className="topbar-sub">
            스마트 쉘프의 구역별 선반 상태 및 적재 현황을 실시간으로 모니터링합니다.
          </span>
        </div>
        <Button pill icon={Plus} onClick={addShelf}>
          새 선반 추가
        </Button>
      </div>

      {unregistered.length > 0 && (
        <div className="form-success">
          미등록 기기 {unregistered.length}대 감지 — {unregistered.map((d) => d.id).join(', ')} ·
          아래 배치 슬롯을 클릭해 위치를 지정하세요.
        </div>
      )}

      {loading ? (
        <div className="t-empty">데이터를 불러오는 중입니다...</div>
      ) : (
        <div className="shelves-area">
          {configs.map((config) => (
            <div key={config.id} className="shelf-card">
              <div className="shelf-card-head">
                <div className="shelf-card-head-left">
                  <span className="shelf-card-title">선반 {config.id}</span>
                  <span className="shelf-size-pill">
                    {config.rows}행 × {config.cols}열
                  </span>
                </div>
                <span className="shelf-edit-btn" title="선반 편집">
                  <Pencil size={24} />
                </span>
              </div>

              <div className="shelf-grid-wrap">
                <div className="shelf-grid">
                  {Array.from({ length: config.rows }, (_, rIdx) => {
                    const row = rIdx + 1;
                    return (
                      <div key={row} className="shelf-grid-row">
                        {Array.from({ length: config.cols }, (_, cIdx) => {
                          const col = cIdx + 1;
                          const device = devices.find(
                            (d) =>
                              d.status === 'registered' &&
                              d.parent_shelf === config.id &&
                              d.row === row &&
                              d.col === col,
                          );

                          if (device) {
                            return (
                              <div key={col} className="slot slot-occupied">
                                <span className="slot-name">{device.name ?? device.id}</span>
                                <span className="slot-device">{device.id}</span>
                                <div className="slot-meta">
                                  <span className="slot-meta-item">
                                    <Weight size={17} />
                                    {device.weight.toFixed(1)}kg
                                  </span>
                                  <span
                                    className={`slot-meta-item battery${device.battery <= 20 ? ' low' : ''}`}
                                  >
                                    <BatteryFull size={19} />
                                    {device.battery}%
                                  </span>
                                  {device.led_on && (
                                    <span className="slot-meta-item led" title="LED 점등 중">
                                      <Lightbulb size={17} />
                                    </span>
                                  )}
                                </div>
                              </div>
                            );
                          }

                          if (unregistered.length > 0) {
                            return (
                              <div
                                key={col}
                                className="slot slot-drop"
                                onClick={() => openRegister({ shelfId: config.id, row, col })}
                              >
                                <CirclePlus size={32} />
                                여기에 배치
                              </div>
                            );
                          }

                          return (
                            <div key={col} className="slot slot-empty">
                              빈 슬롯
                            </div>
                          );
                        })}
                      </div>
                    );
                  })}
                </div>

                <button
                  className="add-col-btn"
                  title="열 추가"
                  onClick={() => updateConfig(config.id, { cols: config.cols + 1 })}
                >
                  <Plus size={26} />
                </button>
              </div>

              <button
                className="add-row-btn"
                onClick={() => updateConfig(config.id, { rows: config.rows + 1 })}
              >
                <Plus size={24} />행 추가
              </button>
            </div>
          ))}
        </div>
      )}

      {/* 미등록 기기 배치 모달 */}
      {target && (
        <div className="modal-overlay" onClick={() => setTarget(null)}>
          <form className="modal" onClick={(e) => e.stopPropagation()} onSubmit={handleRegister}>
            <h3 className="modal-title">
              기기 등록 — 선반 {target.shelfId} · {target.row}행 {target.col}열
            </h3>

            <div className="input-group">
              <span className="input-label">미등록 기기</span>
              <select
                className="input-field"
                value={deviceId}
                onChange={(e) => setDeviceId(e.target.value)}
              >
                {unregistered.map((d) => (
                  <option key={d.id} value={d.id}>
                    {d.id} (배터리 {d.battery}%)
                  </option>
                ))}
              </select>
            </div>

            <Input
              label="수납칸 이름"
              placeholder="예: 수납칸 A7"
              value={slotName}
              onChange={(e) => setSlotName(e.target.value)}
            />

            {modalError && <div className="form-error">{modalError}</div>}

            <div className="modal-actions">
              <Button type="button" variant="outline" onClick={() => setTarget(null)}>
                취소
              </Button>
              <Button type="submit">등록</Button>
            </div>
          </form>
        </div>
      )}
    </div>
  );
}
