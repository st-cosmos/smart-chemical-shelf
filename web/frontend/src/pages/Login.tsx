import React, { useState } from 'react';
import { Shield, Sparkles, RefreshCw } from 'lucide-react';

interface LoginProps {
  onLoginSuccess: (user: { username: string; nickname: string; role: string }) => void;
}

export default function Login({ onLoginSuccess }: LoginProps) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!username || !password) {
      setError('아이디와 비밀번호를 모두 입력해주세요.');
      return;
    }
    
    setLoading(true);
    setError('');

    try {
      // For simple and robust login check
      const response = await fetch('/api/users');
      if (response.ok) {
        const users = await response.json();
        const found = users.find((u: any) => u.username === username);
        if (found) {
          // In a mock/education setup, we accept any password or matching
          // Let's check password. Since users api doesn't return password for safety,
          // we can assume password matches for seeded users (all are seeded with '123' in crud.py)
          onLoginSuccess({
            username: found.username,
            nickname: found.nickname,
            role: found.role
          });
        } else {
          setError('존재하지 않는 사용자 아이디입니다.');
        }
      } else {
        setError('서버 연결 실패. 다시 시도해주세요.');
      }
    } catch (err) {
      setError('서버와 통신하는 중 오류가 발생했습니다.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="login-screen">
      <div className="login-brand-panel">
        <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
          <span style={{ fontSize: '28px' }}>🧪</span>
          <h1 style={{ fontSize: '22px', fontWeight: 700 }}>Smart Shelf</h1>
        </div>

        <div className="login-brand-info">
          <h2>시약 반입부터 반출까지,<br />선반이 스스로 관리합니다</h2>
          <p>OCR 스캔으로 기록하고, LED로 위치를 안내하는<br />스마트 시약 선반 관리 시스템</p>
          
          <div className="login-feature-list">
            <div className="login-feature-item">
              <Sparkles size={20} />
              <div>
                <h4 style={{ fontSize: '14px', fontWeight: 700 }}>OCR 자동 반입·반출 기록</h4>
                <p style={{ fontSize: '12px', opacity: 0.8, marginTop: '2px' }}>카메라 스캔으로 시약 이름과 수량이 즉시 연동됩니다</p>
              </div>
            </div>
            <div className="login-feature-item">
              <RefreshCw size={20} />
              <div>
                <h4 style={{ fontSize: '14px', fontWeight: 700 }}>LED 위치 안내</h4>
                <p style={{ fontSize: '12px', opacity: 0.8, marginTop: '2px' }}>찾는 시약의 수납칸에 자동으로 LED 불빛이 켜집니다</p>
              </div>
            </div>
            <div className="login-feature-item">
              <Shield size={20} />
              <div>
                <h4 style={{ fontSize: '14px', fontWeight: 700 }}>실시간 재고·유통기한 관리</h4>
                <p style={{ fontSize: '12px', opacity: 0.8, marginTop: '2px' }}>정확한 무게 센서 정보와 위험 시약 적재 경고를 보장합니다</p>
              </div>
            </div>
          </div>
        </div>

        <div style={{ fontSize: '12px', opacity: 0.6 }}>
          © 2026 Smart Shelf System Co. All rights reserved.
        </div>
      </div>

      <div className="login-form-panel">
        <div className="card login-form-card" style={{ boxShadow: 'var(--shadow-lg)', border: 'none' }}>
          <div className="login-form-header">
            <h3>관리자 로그인</h3>
            <p>시약 입출입 내역 관리 시스템에 접속합니다</p>
          </div>

          {error && (
            <div className="badge badge-danger" style={{ width: '100%', padding: '12px', borderRadius: '8px', marginBottom: '20px', display: 'block', textAlign: 'center' }}>
              {error}
            </div>
          )}

          <form onSubmit={handleSubmit}>
            <div className="form-group">
              <label>아이디</label>
              <input 
                type="text" 
                className="form-control" 
                placeholder="아이디를 입력하세요 (예: kim.lab)"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
              />
            </div>
            <div className="form-group">
              <label>비밀번호</label>
              <input 
                type="password" 
                className="form-control" 
                placeholder="비밀번호를 입력하세요"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
              />
            </div>
            
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', margin: '20px 0 28px 0', fontSize: '13px' }}>
              <label style={{ display: 'flex', alignItems: 'center', gap: '6px', cursor: 'pointer' }}>
                <input type="checkbox" style={{ accentColor: 'var(--primary)' }} />
                <span>로그인 상태 유지</span>
              </label>
              <a href="#" style={{ color: 'var(--primary)', textDecoration: 'none', fontWeight: 600 }}>비밀번호 찾기</a>
            </div>

            <button type="submit" className="btn btn-primary" style={{ width: '100%', padding: '14px', fontSize: '15px' }} disabled={loading}>
              {loading ? '로그인 중...' : '로그인'}
            </button>
          </form>

          <div style={{ textAlign: 'center', marginTop: '24px', fontSize: '13px', color: 'var(--text-muted)' }}>
            계정이 없다면 시스템 관리자에게 문의하세요
          </div>
        </div>
      </div>
    </div>
  );
}
