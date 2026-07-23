import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { Ellipsis, UserPlus } from 'lucide-react';
import Badge, { roleBadgeVariant } from '../components/Badge';
import Input from '../components/Input';
import SearchBar from '../components/SearchBar';
import { getJSON, postJSON } from '../api';
import type { User } from '../types';

const ROLES = ['관리자', '연구원'] as const;

function formatDate(iso: string): string {
  return iso ? iso.slice(0, 10) : '-';
}

/** 웹 로그인용 비밀번호 규칙: 8자 이상, 숫자·특수문자 포함. 문제 있으면 메시지를, 없으면 null 을 반환. */
function validatePassword(pw: string): string | null {
  if (pw.length < 8) return '비밀번호는 8자 이상이어야 합니다.';
  if (!/\d/.test(pw)) return '비밀번호에 숫자를 포함해야 합니다.';
  if (!/[^A-Za-z0-9]/.test(pw)) return '비밀번호에 특수문자를 포함해야 합니다.';
  return null;
}

/** 앱 로그인용 PIN 규칙: 정확히 4자리 숫자. */
function validatePin(pin: string): string | null {
  if (!/^\d{4}$/.test(pin)) return 'PIN번호는 4자리 숫자여야 합니다.';
  return null;
}

export default function UserRegister() {
  const [users, setUsers] = useState<User[]>([]);
  const [search, setSearch] = useState('');

  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [nickname, setNickname] = useState('');
  const [pin, setPin] = useState('');
  const [role, setRole] = useState<string>('연구원');
  const [submitting, setSubmitting] = useState(false);
  const [notice, setNotice] = useState<{ ok: boolean; text: string } | null>(null);

  const fetchUsers = async () => {
    try {
      setUsers(await getJSON<User[]>('/api/users'));
    } catch {
      // 무시
    }
  };

  useEffect(() => {
    fetchUsers();
  }, []);

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (submitting) return;
    if (!username || !password || !nickname || !pin) {
      setNotice({ ok: false, text: '아이디·비밀번호·별명·PIN번호를 모두 입력하세요.' });
      return;
    }
    const pwError = validatePassword(password);
    if (pwError) {
      setNotice({ ok: false, text: pwError });
      return;
    }
    const pinError = validatePin(pin);
    if (pinError) {
      setNotice({ ok: false, text: pinError });
      return;
    }
    setSubmitting(true);
    setNotice(null);
    try {
      const created = await postJSON<User>('/api/users/register', {
        username,
        password,
        nickname,
        pin,
        role,
      });
      setNotice({ ok: true, text: `${created.nickname} (${created.role}) 계정이 생성되었습니다.` });
      setUsername('');
      setPassword('');
      setNickname('');
      setPin('');
      setRole('연구원');
      await fetchUsers();
    } catch (err) {
      setNotice({ ok: false, text: err instanceof Error ? err.message : '등록에 실패했습니다.' });
    } finally {
      setSubmitting(false);
    }
  };

  const filtered = users.filter((u) => {
    const q = search.trim().toLowerCase();
    if (!q) return true;
    return u.nickname.toLowerCase().includes(q) || u.username.toLowerCase().includes(q);
  });

  return (
    <div className="page">
      <div className="topbar">
        <div className="topbar-info">
          <h1 className="topbar-title">사용자 등록</h1>
          <span className="topbar-sub">태블릿 앱과 웹에서 사용할 계정을 생성합니다</span>
        </div>
      </div>

      <div className="register-layout">
        {/* 좌: 등록 폼 카드 */}
        <form className="card form-card" onSubmit={handleSubmit}>
          <h3 className="form-card-title">새 사용자 정보</h3>

          <Input
            label="아이디"
            placeholder="영문·숫자 4자 이상"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
          />
          <Input
            label="비밀번호"
            type="password"
            placeholder="8자 이상, 숫자·특수문자 포함"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
          <Input
            label="별명"
            placeholder="앱 프로필에 표시될 이름"
            value={nickname}
            onChange={(e) => setNickname(e.target.value)}
          />
          <Input
            label="PIN번호"
            placeholder="4자리 숫자"
            inputMode="numeric"
            maxLength={4}
            value={pin}
            onChange={(e) => setPin(e.target.value.replace(/\D/g, '').slice(0, 4))}
          />

          <div className="input-group">
            <span className="input-label">권한</span>
            <div className="segment">
              {ROLES.map((r) => (
                <button
                  key={r}
                  type="button"
                  className={role === r ? 'active' : ''}
                  onClick={() => setRole(r)}
                >
                  {r}
                </button>
              ))}
            </div>
          </div>

          {notice && <div className={notice.ok ? 'form-success' : 'form-error'}>{notice.text}</div>}

          <button type="submit" className="btn btn-primary btn-cta" disabled={submitting}>
            <UserPlus size={16} />
            {submitting ? '등록 중...' : '사용자 등록'}
          </button>
        </form>

        {/* 우: 사용자 목록 카드 */}
        <div className="card user-list-card">
          <div className="user-list-head">
            <h3>등록된 사용자 {users.length}명</h3>
            <SearchBar value={search} onChange={setSearch} placeholder="사용자 검색" width={220} />
          </div>
          <div className="user-list-body">
            {filtered.length === 0 ? (
              <div className="t-empty">표시할 사용자가 없습니다.</div>
            ) : (
              filtered.map((u) => (
                <div key={u.username} className="user-row">
                  <div className="avatar avatar-sm">{u.nickname.charAt(0)}</div>
                  <div className="user-row-info">
                    <span className="user-row-name">{u.nickname}</span>
                    <span className="user-row-id">@{u.username}</span>
                  </div>
                  <Badge variant={roleBadgeVariant(u.role)}>{u.role}</Badge>
                  <span className="user-row-date">등록일 {formatDate(u.created_at)}</span>
                  <span className="user-row-more">
                    <Ellipsis size={18} />
                  </span>
                </div>
              ))
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
