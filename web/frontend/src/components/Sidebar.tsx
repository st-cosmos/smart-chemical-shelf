import React from 'react';
import { Database, LayoutGrid, ShoppingCart, UserPlus, LogOut } from 'lucide-react';

interface SidebarProps {
  activeTab: string;
  setActiveTab: (tab: string) => void;
  currentUser: { username: string; nickname: string; role: string } | null;
  onLogout: () => void;
}

export default function Sidebar({ activeTab, setActiveTab, currentUser, onLogout }: SidebarProps) {
  return (
    <aside className="sidebar">
      <div className="brand">
        <span className="brand-logo">🧪</span>
        <div className="brand-text">
          <h1>Smart Shelf</h1>
          <p>시약 관리 시스템</p>
        </div>
      </div>

      <nav className="nav-links">
        <button 
          className={`nav-item ${activeTab === 'inventory' ? 'active' : ''}`}
          onClick={() => setActiveTab('inventory')}
        >
          <Database size={20} />
          <span>재고 관리</span>
        </button>
        <button 
          className={`nav-item ${activeTab === 'shelves' ? 'active' : ''}`}
          onClick={() => setActiveTab('shelves')}
        >
          <LayoutGrid size={20} />
          <span>선반 관리</span>
        </button>
        <button 
          className={`nav-item ${activeTab === 'orders' ? 'active' : ''}`}
          onClick={() => setActiveTab('orders')}
        >
          <ShoppingCart size={20} />
          <span>주문 관리</span>
        </button>
        <button 
          className={`nav-item ${activeTab === 'users' ? 'active' : ''}`}
          onClick={() => setActiveTab('users')}
        >
          <UserPlus size={20} />
          <span>사용자 등록</span>
        </button>
      </nav>

      {currentUser && (
        <div className="user-profile">
          <div className="user-avatar">
            {currentUser.nickname.charAt(0)}
          </div>
          <div className="user-info">
            <h4>{currentUser.nickname}</h4>
            <p>{currentUser.role}</p>
          </div>
          <button 
            onClick={onLogout} 
            className="btn btn-outline" 
            style={{ padding: '6px', marginLeft: 'auto', border: 'none', background: 'transparent' }}
            title="로그아웃"
          >
            <LogOut size={16} className="text-muted" />
          </button>
        </div>
      )}
    </aside>
  );
}
