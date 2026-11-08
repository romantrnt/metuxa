#include "secure_transport.hpp"
#include <algorithm>
#include <cstring>
#include <limits>
#include <cstdio>
#include <openssl/crypto.h>
#include <openssl/evp.h>
#include <openssl/rand.h>
#include <openssl/x509.h>

namespace metashare::security {
std::string hex(const std::uint8_t* p, std::size_t n) {
    constexpr char digits[] = "0123456789abcdef";
    std::string s(n * 2, '0');
    for (std::size_t i = 0; i < n; ++i) { s[i*2] = digits[p[i] >> 4]; s[i*2+1] = digits[p[i] & 15]; }
    return s;
}
bool unhex(std::string_view s, std::uint8_t* out, std::size_t n) {
    if (s.size() != n * 2) return false;
    auto digit = [](char c) { if (c >= '0' && c <= '9') return c - '0'; if (c >= 'a' && c <= 'f') return c - 'a' + 10; if (c >= 'A' && c <= 'F') return c - 'A' + 10; return -1; };
    for (std::size_t i=0; i<n; ++i) { int a=digit(s[i*2]), b=digit(s[i*2+1]); if (a<0 || b<0) return false; out[i]=static_cast<std::uint8_t>((a<<4)|b); }
    return true;
}
namespace {
std::string pairing_hash(std::string_view domain, std::initializer_list<std::string_view> fields) {
    std::vector<std::uint8_t> bytes(domain.begin(), domain.end());
    bytes.push_back(0);
    for (auto field : fields) {
        const auto start = bytes.size();
        bytes.resize(start + field.size() / 2);
        if (field.size() % 2 || !unhex(field, bytes.data() + start, field.size() / 2)) return {};
    }
    std::array<std::uint8_t,32> digest{}; unsigned size=0;
    const bool ok=EVP_Digest(bytes.data(),bytes.size(),digest.data(),&size,EVP_sha256(),nullptr)==1 && size==digest.size();
    OPENSSL_cleanse(bytes.data(),bytes.size());
    return ok ? hex(digest.data(),digest.size()) : std::string{};
}
}
std::string pairing_commitment(std::string_view fingerprint, std::string_view client_nonce) {
    if(fingerprint.size()!=64 || client_nonce.size()!=64) return {};
    return pairing_hash("metuxa-pair1-client-commit", {fingerprint,client_nonce});
}
std::string pairing_comparison(std::string_view fingerprint, std::string_view client_nonce,
                               std::string_view server_nonce, std::string_view request_id) {
    if(fingerprint.size()!=64 || client_nonce.size()!=64 || server_nonce.size()!=64 || request_id.size()!=32) return {};
    const auto value=pairing_hash("metuxa-pair1-compare", {fingerprint,client_nonce,server_nonce,request_id});
    std::array<std::uint8_t,32> digest{};
    if(!unhex(value,digest.data(),digest.size())) return {};
    const std::uint32_t number=(std::uint32_t(digest[0])<<24)|(std::uint32_t(digest[1])<<16)|
                               (std::uint32_t(digest[2])<<8)|digest[3];
    char digits[11]; std::snprintf(digits,sizeof digits,"%010llu",static_cast<unsigned long long>(number));
    return std::string(digits,5)+" "+std::string(digits+5,5);
}
bool PairingGate::window_event(bool open) {
    return notify_(std::string("{\"type\":\"pairing_window\",\"state\":\"")+(open?"open":"closed")+
                   "\",\"open\":"+(open?"true":"false")+",\"expires_seconds\":"+(open?"60":"0")+"}");
}
void PairingGate::finish_locked(std::string_view reason) {
    if(!active_) return;
    // Reasons are internal constants, never peer-supplied strings.
    notify_("{\"type\":\"pairing_closed\",\"id\":\""+active_->id+"\",\"reason\":\""+std::string(reason)+"\"}");
    active_.reset(); presented_=false; decision_=Decision::Missing;
}
void PairingGate::tick_locked() {
    const auto now=Clock::now();
    if(armed_ && now>=window_deadline_) { armed_=false; window_event(false); }
    if(active_ && now>=active_->deadline) finish_locked("expired");
}
void PairingGate::tick() { std::lock_guard lock(mutex_); tick_locked(); }
bool PairingGate::arm() {
    std::lock_guard lock(mutex_); tick_locked();
    const char* reason=active_?"busy":attempts_>=10?"limit":
        (attempts_ && Clock::now()-last_attempt_<std::chrono::seconds(2))?"cooldown":nullptr;
    if(reason) { notify_(std::string("{\"type\":\"pairing_unavailable\",\"reason\":\"")+reason+"\"}"); return false; }
    window_deadline_=Clock::now()+std::chrono::seconds(60);
    armed_=window_event(true); return armed_;
}
void PairingGate::close() {
    std::lock_guard lock(mutex_);
    if(armed_) { armed_=false; window_event(false); }
    finish_locked("rejected");
}
std::optional<PairingGate::Ticket> PairingGate::reserve() {
    std::lock_guard lock(mutex_); tick_locked();
    if(!armed_ || active_ || attempts_>=10) return {};
    std::array<std::uint8_t,48> random{};
    if(RAND_bytes(random.data(),random.size())!=1) { armed_=false; window_event(false); return {}; }
    armed_=false; ++attempts_; last_attempt_=Clock::now();
    if(!window_event(false)) return {};
    active_=Ticket{hex(random.data(),16),hex(random.data()+16,32),window_deadline_};
    presented_=false; decision_=Decision::Waiting; return active_;
}
bool PairingGate::present(const std::string& id,const std::string& code,const std::string& peer) {
    std::lock_guard lock(mutex_); tick_locked();
    // Caller uses inet_ntop. Reject anything that could escape a status field.
    if(!active_ || active_->id!=id || presented_ || code.size()!=11 || code[5]!=' ' ||
       code.substr(0,5).find_first_not_of("0123456789")!=std::string::npos ||
       code.substr(6).find_first_not_of("0123456789")!=std::string::npos || peer.empty() ||
       peer.find_first_not_of("0123456789.")!=std::string::npos) return false;
    auto remaining=std::chrono::duration_cast<std::chrono::milliseconds>(active_->deadline-Clock::now()).count();
    presented_=notify_("{\"type\":\"pairing_request\",\"id\":\""+id+"\",\"code\":\""+code+
                       "\",\"peer\":\""+peer+"\",\"expires_seconds\":"+std::to_string((remaining+999)/1000)+"}");
    if(!presented_) finish_locked("invalid");
    return presented_;
}
bool PairingGate::decide(std::string_view id,bool approve) {
    std::lock_guard lock(mutex_); tick_locked();
    if(!active_ || active_->id!=id || !presented_ || decision_!=Decision::Waiting) return false;
    decision_=approve?Decision::Approved:Decision::Rejected; return true;
}
PairingGate::Decision PairingGate::decision(std::string_view id) {
    std::lock_guard lock(mutex_); tick_locked();
    return active_ && active_->id==id ? decision_ : Decision::Missing;
}
bool PairingGate::issue(std::string_view id,const std::function<bool()>& send) {
    std::lock_guard lock(mutex_); tick_locked();
    if(!active_ || active_->id!=id || !presented_ || decision_!=Decision::Approved) return false;
    // No close, deadline transition or second decision can occur between the
    // final check and token issuance. A prior close/expiry always wins.
    bool sent=false;
    try { sent=send(); } catch(...) { finish_locked("disconnected"); throw; }
    finish_locked(sent?"approved":"disconnected"); return sent;
}
void PairingGate::finish(std::string_view id,std::string_view reason) {
    std::lock_guard lock(mutex_);
    if(active_ && active_->id==id) finish_locked(reason);
}
ServerCredentials::~ServerCredentials() {
    if (!code_.empty()) OPENSSL_cleanse(code_.data(), code_.size());
    SSL_CTX_free(ctx_);
}
bool ServerCredentials::authenticate(std::string_view code) const {
    std::array<std::uint8_t,16> a{},b{};
    const bool valid = unhex(code,a.data(),a.size()) && unhex(code_,b.data(),b.size());
    const bool same = valid && CRYPTO_memcmp(a.data(),b.data(),a.size()) == 0;
    OPENSSL_cleanse(a.data(),a.size()); OPENSSL_cleanse(b.data(),b.size());
    return same;
}
std::shared_ptr<ServerCredentials> ServerCredentials::create(std::string& error) {
    auto result = std::shared_ptr<ServerCredentials>(new ServerCredentials);
    EVP_PKEY_CTX* kc = EVP_PKEY_CTX_new_id(EVP_PKEY_EC, nullptr);
    EVP_PKEY* key = nullptr;
    X509* cert = X509_new();
    std::array<std::uint8_t,16> secret{};
    bool ok = kc && cert && EVP_PKEY_keygen_init(kc)>0 &&
        EVP_PKEY_CTX_set_ec_paramgen_curve_nid(kc,NID_X9_62_prime256v1)>0 &&
        EVP_PKEY_keygen(kc,&key)>0 && RAND_bytes(secret.data(),secret.size()) == 1;
    if (ok) {
        X509_set_version(cert,2);
        ASN1_INTEGER_set(X509_get_serialNumber(cert),1);
        X509_gmtime_adj(X509_getm_notBefore(cert),-60);
        X509_gmtime_adj(X509_getm_notAfter(cert),86400*30);
        X509_set_pubkey(cert,key);
        X509_NAME* name=X509_get_subject_name(cert);
        ok = X509_NAME_add_entry_by_txt(name,"CN",MBSTRING_ASC,
                reinterpret_cast<const unsigned char*>("Metuxa ephemeral server"),-1,-1,0)==1 &&
             X509_set_issuer_name(cert,name)==1 && X509_sign(cert,key,EVP_sha256())>0;
        result->ctx_=SSL_CTX_new(TLS_server_method());
        ok = ok && result->ctx_ && SSL_CTX_set_min_proto_version(result->ctx_,TLS1_2_VERSION)==1 &&
             SSL_CTX_use_certificate(result->ctx_,cert)==1 &&
             SSL_CTX_use_PrivateKey(result->ctx_,key)==1 && SSL_CTX_check_private_key(result->ctx_)==1;
        if (ok) {
            SSL_CTX_set_options(result->ctx_,SSL_OP_NO_COMPRESSION|SSL_OP_NO_RENEGOTIATION|SSL_OP_NO_TICKET);
            SSL_CTX_set_session_cache_mode(result->ctx_,SSL_SESS_CACHE_OFF);
            ok = SSL_CTX_set_cipher_list(result->ctx_,"ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-ECDSA-AES128-GCM-SHA256") == 1;
            std::array<std::uint8_t,EVP_MAX_MD_SIZE> digest{}; unsigned int size=0;
            ok = ok && X509_digest(cert,EVP_sha256(),digest.data(),&size)==1 && size==32;
            result->fingerprint_=hex(digest.data(),size);
            result->code_=hex(secret.data(),secret.size());
        }
    }
    OPENSSL_cleanse(secret.data(),secret.size());
    EVP_PKEY_CTX_free(kc); EVP_PKEY_free(key); X509_free(cert);
    if (!ok) { error="Unable to create ephemeral TLS credentials"; return {}; }
    return result;
}
void MediaCipher::clear() {
    OPENSSL_cleanse(key_.data(),key_.size()); OPENSSL_cleanse(prefix_.data(),prefix_.size());
    direction_=0; next_=1; highest_=0; received_.reset();
}
bool MediaCipher::generate(std::uint8_t d) {
    clear();
    if ((d!=kServer && d!=kClient) || RAND_bytes(key_.data(),key_.size())!=1 || RAND_bytes(prefix_.data(),prefix_.size())!=1) { clear(); return false; }
    direction_=d; return true;
}
bool MediaCipher::configure(std::string_view k,std::string_view p,std::uint8_t d) {
    clear();
    if ((d!=kServer && d!=kClient) || !unhex(k,key_.data(),key_.size()) || !unhex(p,prefix_.data(),prefix_.size())) { clear(); return false; }
    direction_=d; return true;
}
std::string MediaCipher::key_hex() const { return hex(key_.data(),key_.size()); }
std::string MediaCipher::prefix_hex() const { return hex(prefix_.data(),prefix_.size()); }
bool MediaCipher::seal(const std::uint8_t* p,std::size_t n,std::vector<std::uint8_t>& out) {
    out.clear();
    // Stop well before GCM's per-key usage bound (and signed Java counter wrap).
    if (!direction_ || !p || n==0 || n>kMaxPlaintext || next_ >= (1ULL<<32)) return false;
    out.resize(n+kOverhead); std::memcpy(out.data(),"MXG1",4); out[4]=direction_; out[5]=out[6]=out[7]=0;
    const auto counter=next_++;
    for (int i=0;i<8;++i) out[8+i]=static_cast<std::uint8_t>(counter>>(56-i*8));
    std::array<std::uint8_t,12> nonce{}; std::copy(prefix_.begin(),prefix_.end(),nonce.begin()); std::copy(out.begin()+8,out.begin()+16,nonce.begin()+4);
    EVP_CIPHER_CTX* c=EVP_CIPHER_CTX_new(); int len=0,total=0;
    bool ok=c && EVP_EncryptInit_ex(c,EVP_aes_256_gcm(),nullptr,key_.data(),nonce.data())==1 &&
        EVP_EncryptUpdate(c,nullptr,&len,out.data(),16)==1 && EVP_EncryptUpdate(c,out.data()+16,&len,p,n)==1;
    total=len;
    ok=ok && EVP_EncryptFinal_ex(c,out.data()+16+total,&len)==1 && EVP_CIPHER_CTX_ctrl(c,EVP_CTRL_GCM_GET_TAG,16,out.data()+16+n)==1;
    EVP_CIPHER_CTX_free(c); if (!ok) out.clear(); return ok;
}
bool MediaCipher::open(const std::uint8_t* p,std::size_t n,std::vector<std::uint8_t>& out) {
    out.clear();
    if (!direction_ || !p || n<=kOverhead || n>kMaxPlaintext+kOverhead || std::memcmp(p,"MXG1",4)!=0 || p[4]!=direction_ || p[5] || p[6] || p[7]) return false;
    std::uint64_t seq=0; for(int i=8;i<16;++i) seq=(seq<<8)|p[i];
    if (seq==0 || seq>=(1ULL<<32) || (seq<=highest_ && (highest_-seq>=received_.size() || received_[highest_-seq]))) return false;
    std::array<std::uint8_t,12> nonce{}; std::copy(prefix_.begin(),prefix_.end(),nonce.begin()); std::copy(p+8,p+16,nonce.begin()+4);
    out.resize(n-kOverhead); EVP_CIPHER_CTX* c=EVP_CIPHER_CTX_new(); int len=0,total=0;
    bool ok=c && EVP_DecryptInit_ex(c,EVP_aes_256_gcm(),nullptr,key_.data(),nonce.data())==1 &&
        EVP_DecryptUpdate(c,nullptr,&len,p,16)==1 && EVP_DecryptUpdate(c,out.data(),&len,p+16,out.size())==1;
    total=len;
    ok=ok && EVP_CIPHER_CTX_ctrl(c,EVP_CTRL_GCM_SET_TAG,16,const_cast<std::uint8_t*>(p+n-16))==1 && EVP_DecryptFinal_ex(c,out.data()+total,&len)==1;
    EVP_CIPHER_CTX_free(c);
    if (!ok) { OPENSSL_cleanse(out.data(),out.size()); out.clear(); return false; }
    if (seq>highest_) { auto delta=seq-highest_; if(delta>=received_.size()) received_.reset(); else received_<<=delta; highest_=seq; }
    received_.set(highest_-seq); return true;
}
} // namespace metashare::security
