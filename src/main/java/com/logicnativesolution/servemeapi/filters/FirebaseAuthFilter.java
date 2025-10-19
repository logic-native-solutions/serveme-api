package com.logicnativesolution.servemeapi.filters;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Security filter that accepts Firebase ID tokens sent as Authorization: Bearer <token>.
 * Uses reflection to avoid a hard dependency on the Firebase Admin SDK at compile time.
 *
 * IMPORTANT: On verification failure, this filter now falls through without 401,
 * so that downstream JWT authentication (or other mechanisms) can try.
 */
@Component
public class FirebaseAuthFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String idToken = header.substring(7);
            try {
                // Reflectively call: FirebaseAuth.getInstance().verifyIdToken(idToken)
                Class<?> firebaseAuthClass = Class.forName("com.google.firebase.auth.FirebaseAuth");
                var getInstance = firebaseAuthClass.getMethod("getInstance");
                Object authInstance = getInstance.invoke(null);
                var verifyIdToken = firebaseAuthClass.getMethod("verifyIdToken", String.class);
                Object decoded = verifyIdToken.invoke(authInstance, idToken);

                // decoded.getUid()
                String uid = (String) decoded.getClass().getMethod("getUid").invoke(decoded);

                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        uid, null, List.of()
                );
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (ClassNotFoundException e) {
                // Firebase not on classpath: skip and let other auth mechanisms proceed
            } catch (Exception e) {
                // Do not block JWT or other auth: just fall through
            }
        }
        chain.doFilter(request, response);
    }
}
