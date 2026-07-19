import React, { useState, useEffect } from 'react';
import { UserPlus, Search, Shield, ShieldCheck, UserCheck } from 'lucide-react';

interface RegisteredUser {
  username: string;
  nickname: string;
  role: string;
  created_at: string;
}

export default function UserRegister() {
  const [users, setUsers] = useState<RegisteredUser[]>([]);
  const [search, setSearch] = useState('');
  const [loading, setLoading] = useState(true);

  // Form states
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [nickname, setNickname] = useState('');
  const [role, setRole] = useState('연구원');
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');

  const fetchUsers = async () => {
    try {
      const response = await fetch('/api/users');
      if (response.ok) {
        const data = await response.json();
        setUsers(data);
      }
    } catch (err) {
      console.error(err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchUsers();
  }, []);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setSuccess('');

    if (!username || !password || !nickname || !role) {
      setError('모든 필드를 입력해 주세요.');
      return;
    }

    if (username.length < 4) {
      setError('아이디는 4자 이상이어야 합니다.');
      return;
    }

    if (password.length < 4) {
      setError('비밀번호는 4자 이상이어야 합니다.');
      return;
    }

    try {
      const response = await fetch('/api/users/register', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password, nickname, role })
      });

      if (response.ok) {
        setSuccess(`사용자 '${nickname}' 등록이 완료되었습니다.`);
        setUsername('');
        setPassword('');
        setNickname('');
        setRole('연구원');
        fetchUsers();
      } else {
        const err = await response.json();
        setError(err.detail || '등록 실패. 아이디 중복을 확인하세요.');
      }
    } catch (err) {
      setError('서버 연결 실패');
    }
  };

  const getRoleBadge = (roleName: string) => {
    switch (roleName) {
      case '관리자':
        return (
          <span className="badge badge-danger">
            <ShieldCheck size={12} /> 관리자
          </span>
        );
      case '연구원':
        return (
          <span className="badge badge-primary">
            <UserCheck size={12} /> 연구원
          </span>
        );
      default:
        return (
          <span className="badge badge-success">
            <UserCheck size={12} /> 보조원
          </span>
        );
    }
  };

  const filteredUsers = users.filter(u => 
    u.username.toLowerCase().includes(search.toLowerCase()) ||
    u.nickname.toLowerCase().includes(search.toLowerCase())
  );

  return (
    <div className="main-content">
      <header className="topbar">
        <div className="page-title">
          <h2>사용자 등록</h2>
          <p>태블릿 앱과 웹 대시보드에 로그인해 시스템을 이용할 연구실 계정을 생성하고 권한을 분배합니다.</p>
        </div>
      </header>

      <div className="content-body" style={{ gap: '32px' }}>
        {/* Form Card */}
        <div className="card form-card">
          <div className="card-title">새 사용자 정보</div>

          {error && (
            <div className="badge badge-danger" style={{ display: 'block', width: '100%', padding: '10px', borderRadius: '8px', marginBottom: '16px', textAlign: 'center' }}>
              {error}
            </div>
          )}

          {success && (
            <div className="badge badge-success" style={{ display: 'block', width: '100%', padding: '10px', borderRadius: '8px', marginBottom: '16px', textAlign: 'center' }}>
              {success}
            </div>
          )}

          <form onSubmit={handleSubmit}>
            <div className="form-group">
              <label>아이디</label>
              <input 
                type="text" 
                className="form-control" 
                placeholder="영문·숫자 4자 이상 (예: lee.exp)"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
              />
            </div>

            <div className="form-group">
              <label>비밀번호 (PIN Code)</label>
              <input 
                type="password" 
                className="form-control" 
                placeholder="태블릿 로그인 시 사용할 암호"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
              />
            </div>

            <div className="form-group">
              <label>별명 (표시 이름)</label>
              <input 
                type="text" 
                className="form-control" 
                placeholder="예: 이실험 연구원"
                value={nickname}
                onChange={(e) => setNickname(e.target.value)}
              />
            </div>

            <div className="form-group">
              <label>권한</label>
              <div className="radio-group">
                <input 
                  type="radio" 
                  id="role-researcher" 
                  name="role" 
                  value="연구원" 
                  checked={role === '연구원'}
                  onChange={() => setRole('연구원')}
                />
                <label htmlFor="role-researcher" className="radio-label">연구원</label>

                <input 
                  type="radio" 
                  id="role-admin" 
                  name="role" 
                  value="관리자"
                  checked={role === '관리자'}
                  onChange={() => setRole('관리자')}
                />
                <label htmlFor="role-admin" className="radio-label">관리자</label>

                <input 
                  type="radio" 
                  id="role-assistant" 
                  name="role" 
                  value="보조원"
                  checked={role === '보조원'}
                  onChange={() => setRole('보조원')}
                />
                <label htmlFor="role-assistant" className="radio-label">보조원</label>
              </div>
            </div>

            <button type="submit" className="btn btn-primary" style={{ width: '100%', padding: '12px', marginTop: '16px' }}>
              <UserPlus size={16} /> 사용자 등록
            </button>
          </form>
        </div>

        {/* Users List Card */}
        <div className="card table-card">
          <div className="card-title" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <span>등록된 사용자 ({users.length}명)</span>
            <div className="search-container" style={{ width: '220px' }}>
              <Search className="search-icon" size={16} />
              <input 
                type="text" 
                className="search-input" 
                placeholder="사용자 검색"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                style={{ padding: '8px 12px 8px 36px', fontSize: '13px' }}
              />
            </div>
          </div>

          <div className="table-wrapper">
            {loading ? (
              <p style={{ padding: '20px', textAlign: 'center' }}>데이터를 불러오는 중입니다...</p>
            ) : filteredUsers.length === 0 ? (
              <p style={{ padding: '20px', textAlign: 'center', color: 'var(--text-muted)' }}>검색 결과가 없습니다.</p>
            ) : (
              <table>
                <thead>
                  <tr>
                    <th>사용자 이름 (별명)</th>
                    <th>아이디</th>
                    <th>권한</th>
                    <th>등록일</th>
                  </tr>
                </thead>
                <tbody>
                  {filteredUsers.map(u => (
                    <tr key={u.username}>
                      <td style={{ fontWeight: 700, color: 'var(--text-strong)' }}>
                        {u.nickname}
                      </td>
                      <td style={{ color: 'var(--text-muted)' }}>@{u.username}</td>
                      <td>{getRoleBadge(u.role)}</td>
                      <td>{u.created_at.substring(0, 10)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
